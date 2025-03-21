package coursier.maven

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import coursier.core.{
  Classifier,
  Configuration,
  Dependency,
  Extension,
  Info,
  MinimizedExclusions,
  Module,
  ModuleName,
  Organization,
  Overrides,
  Project,
  Publication,
  Type,
  Variant,
  VariantSelector
}
import coursier.version.{Version, VersionConstraint}
import dataclass.data
import coursier.core.VariantPublication

@data class GradleModule(
  formatVersion: String,
  component: GradleModule.Component,
  variants: Seq[GradleModule.Variant] = Nil
) {
  def project: Project = {

    def variantDependencies(variant: GradleModule.Variant) = {
      val variant0 = Variant.Attributes(variant.name)
      variant.dependencies.map { dep =>
        val version = dep.version.toSeq match {
          case Seq(("requires", req)) => VersionConstraint(req)
          case _ => sys.error(s"Unrecognized dependency version shape: ${dep.version}")
        }

        variant0 -> Dependency(
          Module(Organization(dep.group), ModuleName(dep.module), Map.empty),
          version,
          VariantSelector.AttributesBased(Map.empty),
          MinimizedExclusions.zero,
          publication = Publication("", Type.empty, Extension.empty, Classifier.empty),
          optional = false,
          transitive = true
        )
      }
    }

    val relocated = variants.nonEmpty && variants.forall(_.`available-at`.nonEmpty)

    val relocationDependencies = variants.flatMap { variant =>
      variant.`available-at` match {
        case None => Nil
        case Some(availableAt) =>
          val variant0 = Variant.Attributes(variant.name)
          Seq(
            variant0 -> Dependency(
              Module(Organization(availableAt.group), ModuleName(availableAt.module), Map.empty),
              VersionConstraint.fromVersion(Version(availableAt.version)),
              VariantSelector.AttributesBased(Map.empty),
              MinimizedExclusions.zero,
              publication = Publication("", Type.empty, Extension.empty, Classifier.empty),
              optional = false,
              transitive = true
            )
          )
      }
    }

    val dependencies = relocationDependencies ++
      variants.flatMap(variantDependencies)

    val variantsMap = variants
      .map { variant =>
        val relocationEntries =
          if (variant.`available-at`.isEmpty) Nil
          else Seq("$relocated" -> "true")
        Variant.Attributes(variant.name) -> (variant.attributesMap ++ relocationEntries)
      }
      .toMap

    val variantPublications = variants
      .map { variant =>
        val publications = variant.files.map { file =>
          VariantPublication(file.name, file.url)
        }
        Variant.Attributes(variant.name) -> publications
      }
      .toMap

    val baseProject = Project(
      module = Module(Organization(component.group), ModuleName(component.module), Map.empty),
      version0 = Version(component.version),
      dependencies0 = dependencies,
      configurations = GradleModule.defaultConfigurations,
      parent0 = None,
      dependencyManagement = Nil,
      properties = Nil,
      profiles = Nil,
      versions = None,
      snapshotVersioning = None,
      packagingOpt = None,
      relocated = relocated,
      actualVersionOpt0 = None,
      publications0 = Nil,
      info = Info(
        description = "",
        homePage = "",
        developers = Nil,
        publication = None,
        scm = None,
        licenseInfo = Nil
      ),
      overrides = Overrides.empty
    )

    baseProject
      .withVariants(variantsMap)
      .withVariantPublications(variantPublications)
  }
}

object GradleModule {

  final case class StringOrInt(value: String)
  object StringOrInt {
    implicit lazy val codec: JsonValueCodec[StringOrInt] =
      new JsonValueCodec[StringOrInt] {

        val stringCodec: JsonValueCodec[String] = JsonCodecMaker.make
        val intCodec: JsonValueCodec[Int]       = JsonCodecMaker.make

        def nullValue = StringOrInt(stringCodec.nullValue)
        def encodeValue(x: StringOrInt, out: JsonWriter) =
          stringCodec.encodeValue(x.value, out)
        def decodeValue(in: JsonReader, default: StringOrInt) = {
          in.setMark()
          val isString =
            try in.isNextToken('"')
            finally in.rollbackToMark()
          StringOrInt {
            if (isString) stringCodec.decodeValue(in, default.value)
            else intCodec.decodeValue(in, 0).toString
          }
        }
      }
  }

  @data class Component(
    group: String,
    module: String,
    version: String,
    attributes: Map[String, StringOrInt] = Map.empty
  ) {
    lazy val attributesMap = attributes.map {
      case (k, v) =>
        (k, v.value)
    }
  }

  @data class Variant(
    name: String,
    attributes: Map[String, StringOrInt],
    dependencies: Seq[ModuleDependency],
    files: Seq[ModuleFile],
    `available-at`: Option[AvailableAt] = None
  ) {
    lazy val attributesMap = attributes.map {
      case (k, v) =>
        (k, v.value)
    }
    def matches(expectedAttributes: Map[String, Set[String]]): Boolean =
      expectedAttributes.forall {
        case (k, set) =>
          attributesMap.get(k).forall { value =>
            set.contains(value)
          }
      }
  }

  @data class ModuleDependency(
    group: String,
    module: String,
    version: Map[String, String]
  )

  @data class ModuleFile(
    name: String,
    url: String,
    size: Long,
    sha512: String = "",
    sha256: String = "",
    sha1: String = "",
    md5: String = ""
  )

  @data class AvailableAt(
    url: String,
    group: String,
    module: String,
    version: String
  )

  implicit lazy val codec: JsonValueCodec[GradleModule] =
    JsonCodecMaker.make

  val defaultConfigurations: Map[Configuration, Seq[Configuration]] = Map(
    Configuration.compile -> Nil,
    Configuration.runtime -> Nil,
    Configuration.test    -> Nil
  )
}
