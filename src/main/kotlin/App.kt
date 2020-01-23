import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.util.*
import java.util.concurrent.TimeUnit


class App(val rootPomXml: File, val compareCommit: String?, val includeDependents: Boolean) {
    val rootDir = rootPomXml.parentFile

    init {
        if (!rootDir.isAGitRepo()) {
            throw UnsupportedOperationException("the given directory ${rootDir.absolutePath} is not part of a git repository")
        }
    }


    fun run(): List<String> {
        //retrieve current pom.xml with timestamps of each module
        //remove all pom.xmls that are not actual artifacts
        val allModules: Set<Module> = loadModules(rootDir, "--")

        //compare with old list (if one is set), remove all exact duplicates
        val modules = if (compareCommit != null) {
            val oldSet = loadModules(rootDir, compareCommit)
            allModules.filter { !oldSet.contains(it) }.toSet()
        } else {
            allModules
        }

        //to each pom.xml, find dependents, also include them if requested
        val output: Set<Module> = if (includeDependents) {
            val allPoms = findAllPoms(rootDir)
            val foundModules = mutableSetOf<Module>()
            foundModules.addAll(foundModules)
            val queue = ArrayDeque<Module>()
            queue.addAll(modules)
            while (queue.isNotEmpty()) {
                val mod = queue.poll()
                if (!foundModules.contains(mod)) {
                    foundModules.add(mod)
                    val dependents: Set<Module> = findDependents(allPoms, mod)
                    queue.addAll(dependents)
                }
            }
            foundModules
        } else {
            modules
        }

        return output.map { it.moduleId }
    }

    private fun findDependents(allPoms: Map<File, Model>, module: Module): Set<Module> {
        return allPoms.entries.filter {
            it.value.dependencies.map { it.transformDependencyToString() }.contains(module.moduleId)
        }.map { tryParseModule(it.key) }.toSet()
    }

    private fun tryParseModule(pom: File): Module {
        TODO("not yet implemented")
    }

    private fun loadModules(rootDir: File, compareCommit: String): Set<Module> {
        val dirChanges =
            "git ls-tree -r -d --name-only HEAD | while read filename; do   echo \"\$(git log -1 --format=\"%ad\" -- \$filename) \$filename\"; done"
        val listDirsInGeneral = "git ls-tree -r -d --name-only HEAD".runCmdInPwd(rootDir)!!.trim().lines()
        val modifiedAt = listDirsInGeneral.map {
            val op = "git log -1 --format=\"%ad\" -- $it".runCmdInPwd(rootDir)!!
            Pair(it, op.trim().replace("\"", ""))
        }.toMap()
        return modifiedAt.keys.filter { key -> rootDir.resolve(key).resolve("pom.xml").canRead() }
            .map { buildModule(it, modifiedAt[it]!!) }.toSet()
    }

    private fun buildModule(key: String, timeStamp: String): Module {
        val pom = rootDir.resolve(key).resolve("pom.xml")
        return Module(pom, timeStamp)
    }

    private fun findAllPoms(rootDir: File): Map<File, Model> {
        val output = mutableMapOf<File, Model>()
        val cmd = "git ls-tree -r --name-only HEAD | grep --color=never pom.xml"
        output.put(rootPomXml, rootPomXml.parsePOM())
        cmd.runCmdInPwd(rootDir)!!.lines().map { File(it) }.filter { it.canRead() }.forEach {
            output.put(it, it.parsePOM())
        }
        return output
    }
}

data class Module(val location: File, val timeStamp: String) {
    val model = MavenXpp3Reader().read(FileReader(location))
    val moduleId = model.transformToIdString()
}


fun File.isAGitRepo(): Boolean {
    val res = "git rev-parse --is-inside-work-tree".runCmdInPwd(this)
    return res!!.trim().equals(true.toString())
}


fun File.parsePOM(): Model {
    val reader = MavenXpp3Reader()
    val model: Model = reader.read(FileReader(this))
    return model
}

private fun Model.transformToIdString(): String {
    return this.groupId ?: (this.parent.groupId) + "::" + this.artifactId + "::" + this.version ?: (this.parent.version)
}

private fun Dependency.transformDependencyToString(): String {
    return this.groupId + "::" + this.artifactId + "::" + this.version
}


fun String.runCmdInPwd(workingDir: File): String? {
    try {
        val parts = this.split("\\s".toRegex())
        val proc = ProcessBuilder(*parts.toTypedArray())
            .directory(workingDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        proc.waitFor(180, TimeUnit.SECONDS)
        return proc.inputStream.bufferedReader().readText()
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
}