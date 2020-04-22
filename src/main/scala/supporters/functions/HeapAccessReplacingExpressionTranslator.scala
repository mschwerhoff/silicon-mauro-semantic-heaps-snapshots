// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.supporters.functions

import com.typesafe.scalalogging.LazyLogging
import viper.silver.ast
import viper.silicon.Map
import viper.silicon.rules.functionSupporter
import viper.silicon.state.{Identifier, SimpleIdentifier, SuffixedIdentifier, SymbolConverter}
import viper.silicon.state.terms._
import viper.silicon.state.terms.predef.`?h`
import viper.silicon.supporters.ExpressionTranslator
import viper.silver.reporter.{InternalWarningMessage, Reporter}

class HeapAccessReplacingExpressionTranslator(symbolConverter: SymbolConverter,
                                              fresh: (String, Sort) => Var,
                                              resolutionFailureMessage: (ast.Positioned, FunctionData) => String,
                                              stopOnResolutionFailure: (ast.Positioned, FunctionData) => Boolean,
                                              reporter: Reporter)
    extends ExpressionTranslator
       with LazyLogging {

  private var program: ast.Program = _
  private var func: ast.Function = _
  private var data: FunctionData = _
  private var ignoreAccessPredicates = false
  private var failed = false
  private var snap: Term = `?h`

  var functionData: Map[ast.Function, FunctionData] = _

  def translate(program: ast.Program,
                func: ast.Function,
                data: FunctionData)
               : Option[Term] = {

    this.func = func
    this.program = program
    this.data = data
    this.failed = false

    // Unfoldings can update this variable to flatten heap this during body translation
    this.snap = `?h`

    val result = func.body map translate

    if (failed) None else result
  }

  private def translate(exp: ast.Exp): Term = {
    /* Attention: This method is reentrant (via private translate) */
    translate(symbolConverter.toSort _)(exp)
  }

  def translatePostcondition(program: ast.Program,
                             posts: Seq[ast.Exp],
                             data: FunctionData)
                            : Seq[Term] = {

    this.program = program
    this.data = data
    this.failed = false
    this.snap = `?h`

    posts.map(p => translate(symbolConverter.toSort _)(p.whenInhaling))
  }

  def translatePrecondition(program: ast.Program,
                            pres: Seq[ast.Exp],
                            data: FunctionData)
                           : Seq[Term] = {

    this.program = program
    this.data = data
    this.ignoreAccessPredicates = true
    this.failed = false
    this.snap = `?h`

    pres.map(p => translate(symbolConverter.toSort _)(p.whenExhaling))
  }

  /* Attention: Expects some fields, e.g., `program` and `locToSnap`, to be
   * set, depending on which kind of translation is performed.
   * See public `translate` methods.
   */
  override protected def translate(toSort: ast.Type => Sort)
                                  (e: ast.Exp)
                                  : Term =

    e match {
      case _: ast.AccessPredicate | _: ast.MagicWand if ignoreAccessPredicates => True()
      case q: ast.Forall if !q.isPure && ignoreAccessPredicates => True()

      case _: ast.Result => data.formalResult

      case v: ast.AbstractLocalVar =>
        data.formalArgs.get(v) match {
          case Some(t) => t
          case None => Var(Identifier(v.name), toSort(v.typ))
        }

      case eQuant: ast.QuantifiedExp =>
        /* Local variables that are not parameters of the function itself, i.e. quantified
         * and let-bound variables, are translated as-is, e.g. 'x' will be translated to 'x',
         * not to some 'x@i'. If a local variable occurs in a term that was recorded during
         * the well-definedness checking & verification of a function, e.g. a mapping such as
         * 'e.f |-> lookup(...)' from field access to snapshot, the recorded term potentially
         * contains occurrences of such local variables. However, recorded terms contain
         * occurrences where the local variables *are* suffixed, i.e. of the form 'x@i'.
         * Hence, the body of a quantifier is processed after being translated, and each
         * occurrence of 'x@i' is replaced by 'x', for all variables 'x@i' where the prefix
         * 'x' is bound by the surrounding quantifier.
         */
        val tQuant = super.translate(symbolConverter.toSort)(eQuant).asInstanceOf[Quantification]
        val names = tQuant.vars.map(_.id.name)

        tQuant.transform({ case v: Var =>
          v.id match {
            case sid: SuffixedIdentifier if names.contains(sid.prefix) => Var(SimpleIdentifier(sid.prefix), v.sort)
            case _ => v
          }
          case x => x
        })()

      case ast.FieldAccess(rcv, field) =>
        PHeapLookupField(field.name, symbolConverter.toSort(field.typ), this.snap, translate(rcv))

      case ast.Unfolding(ast.PredicateAccessPredicate(ast.PredicateAccess(args, predicate), p), eIn) =>
        var oldSnap = this.snap
        this.snap = PHeapCombine(
          PHeapLookupPredicate(predicate, this.snap, args map translate),
          PHeapRemovePredicate(predicate, this.snap, args map translate)
        )
        var teIn = translate(toSort)(eIn)
        this.snap = oldSnap
        teIn

      case ast.Applying(_, eIn) => translate(toSort)(eIn)

      case eFApp: ast.FuncApp =>
        val silverFunc = program.findFunction(eFApp.funcname)
        val fun = symbolConverter.toFunction(silverFunc)
        val args = eFApp.args map (arg => translate(arg))
        val fapp = App(fun, PHeapRestrict(fun.id.name, this.snap, args) +: args)

        val callerHeight = data.height
        val calleeHeight = functionData(eFApp.func(program)).height

        if (callerHeight < calleeHeight)
          fapp
        else {
          val untaggedSnap = PHeapRestrict(fun.id.name, this.snap, args)
          val callerData = functionData(eFApp.func(program))
          val taggedSnap = callerData.predicateTriggers.keys.foldLeft[Term](untaggedSnap)((h, p) => {
            val tag = Fun(Identifier("PHeap.funTrigger_" ++ p.name), Seq(sorts.PHeap), sorts.PHeap)
            App(tag, Seq(h))
          })
         App(functionSupporter.limitedVersion(fun), taggedSnap +: args)
        }

      case n:ast.WildcardPerm =>
        // TODO: Not perfect injection, could additionally use n.pos.asInstanceOf[ast.HasLineColumn].column
        // TODO: Handle wildcards without source position

        val w = IntLiteral(n.pos.asInstanceOf[ast.HasLineColumn].line)
        // TODO make a meta term
        App(Fun(Identifier("freshWildcard"), Seq(sorts.Int), sorts.Perm), Seq(w))
        //super.translate(symbolConverter.toSort)(e)

      case _ => super.translate(symbolConverter.toSort)(e)
    }

  def getOrFail[K <: ast.Positioned](map: Map[K, Term], key: K, sort: Sort): Term =
    map.get(key) match {
      case Some(s) =>
        s.convert(sort)
      case None =>
        if (!failed && data.verificationFailures.isEmpty) {
          val msg = resolutionFailureMessage(key, data)

          reporter report InternalWarningMessage(msg)
          logger warn msg
        }

        failed = failed || stopOnResolutionFailure(key, data)

        /* TODO: Fresh symbol $unresolved must be a function of all currently quantified variables,
         *       including the formal arguments of a function, if the unresolved expression is from
         *       a function body.
         */
        fresh("$unresolved", sort)
    }
}
