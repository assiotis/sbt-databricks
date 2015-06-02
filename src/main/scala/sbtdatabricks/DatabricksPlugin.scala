package sbtdatabricks

import sbt._
import Keys._
import scala.collection.mutable.{HashMap => MutHashMap, MultiMap => MutMultiMap, Set => MutSet}

object DatabricksPlugin extends AutoPlugin {

  type LibraryName = String
  type ClusterName = String
  type LibraryMap = MutHashMap[LibraryName, MutSet[LibraryListResult]] with MutMultiMap[LibraryName, LibraryListResult]

  object autoImport {

    val dbcUpload = taskKey[(Seq[UploadedLibrary], Seq[UploadedLibrary])](
      "Upload your jar to Databricks Cloud as a Library.")
    val dbcAttach = taskKey[Unit]("Attach your library to a cluster. Restart cluster if dbcRestartOnAttach is " +
      "true, and if necessary.")
    val dbcDeploy = taskKey[Unit]("Upload your library to Databricks Cloud and attach it to clusters. Performs " +
      "dbcUpload and dbcAttach together.")
    val dbcClusters = settingKey[Seq[String]]("List of clusters to attach project to. To attach to all clusters, " +
      "set this as 'ALL_CLUSTERS'.")
    val dbcRestartOnAttach = settingKey[Boolean]("Whether to restart the cluster when a new version of" +
      " your library is attached.")
    val dbcLibraryPath = settingKey[String]("Where in the workspace to add the libraries.")
    val dbcListClusters = taskKey[Unit]("List all available clusters and their states.")
    val dbcRestartClusters = taskKey[Unit]("Restart the given clusters.")

    val dbcApiUrl = taskKey[String]("The URL for the DB API endpoint")
    val dbcUsername = taskKey[String]("The username for Databricks Cloud")
    val dbcPassword = taskKey[String]("The password for Databricks Cloud")

    final val DBC_ALL_CLUSTERS = "ALL_CLUSTERS"
  }

  import autoImport._

  // exposed for testing
  val dbcApiClient = taskKey[DatabricksHttp]("Create client to handle SSL communication.")

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  lazy val dbcFetchLibraries: Def.Initialize[Task[LibraryMap]] = Def.task {
    val libs = dbcApiClient.value.fetchLibraries
    val m = new MutHashMap[String, MutSet[LibraryListResult]] with MutMultiMap[String, LibraryListResult]
    libs.foreach { lib =>
      m.addBinding(lib.name, lib)
    }
    m
  }

  /** Existing instances of this library on Databricks Cloud. */
  lazy val existingLibraries: Def.Initialize[Task[Seq[UploadedLibrary]]] = Def.task {
    val cp = dbcClasspath.value
    val allLibraries = dbcFetchLibraries.value
    val inFolder = dbcLibraryPath.value
    cp.flatMap { jar =>
      allLibraries.get(jar.getName).flatMap { set =>
        val filteredSet = set.filter(lib => lib.folder == inFolder).map { lib =>
          new UploadedLibrary(lib.name, jar, lib.id)
        }
        if (filteredSet.nonEmpty) {
          Some(filteredSet)
        } else {
          None
        }
      }
    }.flatMap(c => c)
  }

  /** Returns all the jars related to this library. */
  lazy val dbcClasspath = Def.task {
    (dbcLocalProjects.value ++ (managedClasspath in Runtime).value.files
      ).filterNot(_.getName startsWith "scala-")
  }

  /**
   * Visits the local dependencies of a project in a multi-project build, and adds the `package`
   * task of that dependency to a sequence, so that when we call dbcClasspath, we get all the local
   * dependencies (given by .dependsOn(project-b)) in addition to any dependencies declared by
   * libraryDependencies.
   */
  private def dbcLocalProjects: Def.Initialize[Task[Seq[File]]] =
    (thisProjectRef, thisProject, state).flatMap {
      (projectRef: ProjectRef, project: ResolvedProject, currentState: State) => {
        // visit all projects that the starting project depends on, and add their package method
        // to a sequence.
        def visit(p: ProjectRef): Seq[Task[java.io.File]] = {
          val extracted = Project.extract(currentState)
          val data = extracted.structure.data
          val depProject = (thisProject in p).get(data).getOrElse(sys.error("Invalid project: " + p))
          val jarFile = (Keys.`package` in Runtime in p).get(data).get
          jarFile +: depProject.dependencies.map {
            case ResolvedClasspathDependency(dep, confMapping) => dep
          }.flatMap(visit).toList
        }
        // projectRef is a project defined in the build file. This would be `root` when the library
        // is small. In Spark, projectRefs would be mllib, sql, streaming, etc... Anything defined
        // as Project(...)
        visit(projectRef).join.map(_.toSet.toSeq)
      }
    }

  // The second boolean is a hack to make execution sequential and to stabilize tests
  val dbcFetchClusters = taskKey[(Seq[Cluster], Boolean)]("Fetch all available clusters.")

  private lazy val dbcClusterSet = Def.setting(dbcClusters.value.toSet)

  private def getRealClusterList(set: Set[ClusterName], all: Seq[Cluster]): Set[ClusterName] = {
    if (set.contains(DBC_ALL_CLUSTERS)) all.map(_.name).toSet
    else set
  }

  private def uploadImpl1(
      client: DatabricksHttp,
      folder: String,
      cp: Seq[File],
      existing: Seq[UploadedLibrary]): (Seq[UploadedLibrary], Seq[UploadedLibrary]) = {
    // TODO: try to figure out dependencies with changed versions
    val toDelete = existing.filter(_.name.contains("-SNAPSHOT"))
    client.deleteLibraries(toDelete)
    // Either upload the newer SNAPSHOT versions, or everything, because they don't exist yet.
      val toUpload = cp.toSet -- existing.map(_.jar) ++ toDelete.map(_.jar)
    val uploaded = toUpload.map { jar =>
      val uploadedLib = client.uploadJar(jar.getName, jar, folder)
      new UploadedLibrary(jar.getName, jar, uploadedLib.id)
    }.toSeq
    (uploaded, toDelete)
  }

  // Delete old SNAPSHOT versions in the Classpath on DBC, and upload all jars that don't exist.
  // Returns the deleted and uploaded libraries.
  private lazy val uploadImpl: Def.Initialize[Task[(Seq[UploadedLibrary], Seq[UploadedLibrary])]] = Def.task {
    val client = dbcApiClient.value
    val folder = dbcLibraryPath.value
    val existing = existingLibraries.value
    val classpath = dbcClasspath.value
    uploadImpl1(client, folder, classpath, existing)
  }

  private lazy val deployImpl: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val client = dbcApiClient.value
    val (allClusters, done) = dbcFetchClusters.value
    val onClusters = getRealClusterList(dbcClusterSet.value, allClusters)
    if (done) {
      Def.taskDyn {
        val oldVersions = existingLibraries.value
        var count = 0
        var clustersToRestart = Set.empty[String]
        // a tuple of the library and the set of clusters to attach it to
        val requiresAttachFromExisting: Seq[(UploadedLibrary, Set[String])] = oldVersions.flatMap { oldLib =>
          count += 1
          val attachedTo = client.isOldVersionAttached(oldLib, allClusters, onClusters)
          if (oldLib.name.contains("-SNAPSHOT")) {
            clustersToRestart ++= attachedTo
            Seq.empty[(UploadedLibrary, Set[String])]
          } else if (attachedTo != onClusters) {
            Seq((oldLib, onClusters -- attachedTo))
          } else {
            Seq.empty[(UploadedLibrary, Set[String])]
          }
        }
        // Hack to make execution sequential
        if (count == oldVersions.length) {
          Def.task {
            val (uploaded, _) =
              uploadImpl1(client, dbcLibraryPath.value, dbcClasspath.value, oldVersions)
            val requiresAttach = requiresAttachFromExisting.toSet ++ uploaded.map((_, onClusters))
            for (libs <- requiresAttach) {
              client.foreachCluster(libs._2, allClusters)(client.attachToCluster(libs._1, _))
            }
            if (dbcRestartOnAttach.value && clustersToRestart.nonEmpty) {
              client.foreachCluster(clustersToRestart, allClusters)(client.restartCluster(_))
            }
          }
        } else {
          Def.task(throw new RuntimeException("Deleting files returned an error."))
        }
      }
    } else {
      Def.task(throw new RuntimeException("Cluster fetch returned an error."))
    }
  }

  val baseDBCSettings: Seq[Setting[_]] = Seq(
    dbcUsername := { 
      sys.error(
        """
          |dbcUsername not defined. Please make sure to add these keys to your build:
          |  dbcUsername := "user"
          |  dbcPassword := "pass"
          |  dbcApiUrl := "https://organization.cloud.databricks.com:34563/api/1.1"
          |  See the sbt-databricks README for more info.
        """.stripMargin)
    },
    dbcPassword := { 
      sys.error(
        """
          |dbcPassword not defined. Please make sure to add these keys to your build:
          |  dbcUsername := "user"
          |  dbcPassword := "pass"
          |  dbcApiUrl := "https://organization.cloud.databricks.com:34563/api/1.1"
          |  See the sbt-databricks README for more info.
        """.stripMargin)
    },
    dbcApiUrl := { 
      sys.error(
        """
          |dbcApiUrl not defined. Please make sure to add these keys to your build:
          |  dbcUsername := "user"
          |  dbcPassword := "pass"
          |  dbcApiUrl := "https://organization.cloud.databricks.com:34563/api/1.1"
          |  See the sbt-databricks README for more info.
        """.stripMargin)
    },
    dbcClusters := Seq.empty[String],
    dbcRestartOnAttach := true,
    dbcLibraryPath := "/",
    dbcApiClient := DatabricksHttp(dbcApiUrl.value, dbcUsername.value, dbcPassword.value),
    dbcFetchClusters := (dbcApiClient.value.fetchClusters, true),
    dbcRestartClusters := {
      val onClusters = dbcClusterSet.value
      val (allClusters, _) = dbcFetchClusters.value
      val client = dbcApiClient.value
      client.foreachCluster(onClusters, allClusters)(client.restartCluster(_))
    },
    dbcListClusters := {
      val (clusters, _) = dbcFetchClusters.value
      clusters.zipWithIndex.foreach { case (cluster, idx) =>
        println(s"${idx + 1}- $cluster")
      }
    },
    dbcUpload := uploadImpl.value,
    dbcAttach <<= Def.taskDyn {
      val client = dbcApiClient.value
      val onClusters = dbcClusterSet.value
      val (allClusters, done) = dbcFetchClusters.value
      if (done) {
        Def.task {
          val libraries = existingLibraries.value
          for (lib <- libraries) {
            client.foreachCluster(onClusters, allClusters)(client.attachToCluster(lib, _))
          }
        }
      } else {
        Def.task(throw new RuntimeException("Wrong ordering of methods"))
      }
    },
    dbcDeploy := deployImpl.value
  )

  override lazy val projectSettings: Seq[Setting[_]] = baseDBCSettings
}

case class UploadedLibraryId(id: String)
case class UploadedLibrary(name: String, jar: File, id: String)
case class Cluster(
    name: String,
    id: String,
    status: String,
    driverIp: String,
    jdbcPort: String,
    numWorkers: Int) {
  override def toString: String = {
    s"Cluster Name: $name, Status: $status, Number of Workers: $numWorkers."
  }
}
case class LibraryListResult(id: String, name: String, folder: String)
case class LibraryStatus(
    id: String,
    name: String,
    folder: String,
    libType: String,
    files: List[String],
    attachAllClusters: Boolean,
    statuses: List[LibraryClusterStatus])
case class LibraryClusterStatus(clusterId: String, status: String)