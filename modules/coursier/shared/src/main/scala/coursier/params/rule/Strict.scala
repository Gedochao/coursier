package coursier.params.rule

import coursier.core.Resolution
import coursier.error.conflict.UnsatisfiedRule
import coursier.graph.Conflict.Conflicted
import coursier.util.ModuleMatcher
import dataclass._

@data class Strict(
  include: Set[ModuleMatcher] = Set(ModuleMatcher.all),
  @since
  exclude: Set[ModuleMatcher] = Set.empty,
  @since
  includeByDefault: Boolean = false,
  @since
  ignoreIfForcedVersion: Boolean = true,
  semVer: Boolean = false
) extends Rule {

  import Strict._

  type C = EvictedDependencies

  def check(res: Resolution): Option[EvictedDependencies] = {

    val conflicts = coursier.graph.Conflict.conflicted(res, semVer = semVer).filter { c =>
      val conflict = c.conflict
      val ignore =
        ignoreIfForcedVersion && res.forceVersions0.get(conflict.module).exists {
          forcedConstraint =>
            val validateInterval = forcedConstraint.interval.contains(conflict.version0)
            def validatePreferredVersions = forcedConstraint.preferred.isEmpty ||
              forcedConstraint.preferred.contains(conflict.version0)
            validateInterval && validatePreferredVersions
        }
      def matches =
        if (includeByDefault)
          include.exists(_.matches(conflict.module)) ||
          !exclude.exists(_.matches(conflict.module))
        else
          include.exists(_.matches(conflict.module)) &&
          !exclude.exists(_.matches(conflict.module))
      !ignore && matches
    }

    if (conflicts.isEmpty)
      None
    else
      Some(new EvictedDependencies(this, conflicts))
  }

  def tryResolve(
    res: Resolution,
    conflict: EvictedDependencies
  ): Either[UnsatisfiableRule, Resolution] =
    Left(new UnsatisfiableRule(res, this, conflict))

  override def repr: String = {
    val b       = new StringBuilder("Strict(")
    var anyElem = false
    if (include.nonEmpty) {
      anyElem = true
      b ++= include.toVector.map(_.matcher.repr).sorted.mkString(" | ")
    }
    if (exclude.nonEmpty) {
      if (anyElem)
        b ++= ", "
      else
        anyElem = true
      b ++= "exclude="
      b ++= exclude.toVector.map(_.matcher.repr).sorted.mkString(" | ")
    }
    if (includeByDefault) {
      if (anyElem)
        b ++= ", "
      else
        anyElem = true
      b ++= "includeByDefault=true"
    }
    if (!ignoreIfForcedVersion) {
      if (anyElem)
        b ++= ", "
      else
        anyElem = true
      b ++= "ignoreIfForcedVersion=false"
    }
    b += ')'
    b.result()
  }
}

object Strict {

  final class EvictedDependencies(
    override val rule: Strict,
    val evicted: Seq[Conflicted]
  ) extends UnsatisfiedRule(
        rule,
        s"Found evicted dependencies:" + System.lineSeparator() +
          evicted.map(_.repr + System.lineSeparator()).mkString
      )

  final class UnsatisfiableRule(
    resolution: Resolution,
    override val rule: Strict,
    override val conflict: EvictedDependencies
  ) extends coursier.error.ResolutionError.UnsatisfiableRule(
        resolution,
        rule,
        conflict,
        conflict.getMessage
      )

}
