package scala.tools.refactoring.implementations.extraction

import scala.tools.refactoring.analysis.VisibilityScopes
import scala.tools.refactoring.common.CompilerAccess

trait ExtractionScopes extends VisibilityScopes { self: CompilerAccess =>
  import global._

  /**
   * Tracks inbound dependencies of a selection in a visibility scope tree
   * and offers consistent insertion transformations for extraction refactorings.
   * Use collectExtractionScopes for construction of extraction scopes.
   */
  case class ExtractionScope(
    selection: Selection,
    scope: VisibilityScope,
    definedDependencies: List[Symbol],
    undefinedDependencies: List[Symbol]) {

    val afterSelection = (pos: Position) => {
      !pos.isRange || pos.start < selection.pos.start
    }
    
    val afterDeclarations = (pos: Position) => {
      !pos.isRange || !scope.pos.isRange || pos.start <= scope.pos.start
    }

    def insertInSeq(stats: List[Tree], insertion: Tree, isBeforeInsertionPoint: Position => Boolean) = {
      val (before, after) = stats.span((t: Tree) => isBeforeInsertionPoint(t.pos))
      before ::: insertion :: after ::: Nil
    }

    def findScopeAndThen(trans: Transformation[Tree, Tree]) = topdown {
      matchingChildren {
        predicate((t: Tree) => t.samePosAndType(scope.enclosing)) &>
          trans
      }
    }

    def insert(insertion: Tree) =
      findScopeAndThen {
        transform {
          case t @ Template(_, _, body) =>
            t copy (body = insertInSeq(body, insertion, afterSelection)) replaces t
          case t @ DefDef(_, _, _, _, _, rhs) =>
            t copy (rhs = mkBlock(insertion :: rhs :: Nil)) replaces t
          case t @ Function(_, body) =>
            t copy (body = mkBlock(insertion :: body :: Nil)) replaces t
          case t @ Block(stats, expr) =>
            mkBlock(insertInSeq(stats :+ expr, insertion, afterDeclarations)) replaces t
        }
      }
  }

  class ExtractionScopePredicate(val p: ExtractionScope => Boolean) {
    def &&(q: ExtractionScopePredicate) =
      new ExtractionScopePredicate(s => p(s) && q.p(s))
    def ||(q: ExtractionScopePredicate) =
      new ExtractionScopePredicate(s => p(s) || q.p(s))
    def unary_! =
      new ExtractionScopePredicate(s => !p(s))
    def apply(es: ExtractionScope) = p(es)
  }

  def isA[T <: VisibilityScope](implicit m: Manifest[T]) =
    new ExtractionScopePredicate(s => m.runtimeClass.isInstance(s.scope))

  def hasNoUndefinedDependencies =
    new ExtractionScopePredicate(s => s.undefinedDependencies.isEmpty)

  def collectExtractionScopes(selection: Selection, pred: ExtractionScopePredicate) = {
    val vs = VisibilityScope(selection)
    val inboundDeps = {
      val usedSymbols = selection.selectedSymbols
      val definedSymbols = selection.allSelectedTrees.collect {
        case t: DefTree => t.symbol
      }
      usedSymbols.diff(definedSymbols)
    }
    
    val shouldUseScope = pred && !isA[PackageScope]

    def inner(vs: VisibilityScope, undefinedDeps: List[Symbol]): List[ExtractionScope] = {
      val definedInVs = vs.symbols
      val es = ExtractionScope(selection, vs, inboundDeps diff undefinedDeps, undefinedDeps)
      val scopes = if (shouldUseScope(es)) es :: Nil else Nil
      vs.visibleScopes match {
        case Nil => scopes
        case children => scopes ::: children.flatMap(inner(_, undefinedDeps union definedInVs))
      }
    }

    inner(vs, Nil)
  }
}