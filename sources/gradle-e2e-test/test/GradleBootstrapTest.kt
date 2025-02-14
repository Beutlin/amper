/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import org.apache.commons.io.output.TeeOutputStream
import org.gradle.tooling.GradleConnector
import org.jetbrains.amper.test.TestUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class GradleBootstrapTest {

    @TempDir
    lateinit var projectPath: Path

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `amper could build itself using version from sources`() {
        // given
        val commonTemplatePath = TestUtil.amperSourcesRoot.resolve("common.module-template.yaml")

        val commonTemplate = commonTemplatePath.inputStream().use { Yaml().load<Map<String, Any>>(it) }
        val yamlSettings = commonTemplate.getValue("settings") as Map<String, Any>
        val yamlPublishing = yamlSettings.getValue("publishing") as Map<String, String>
        val version = yamlPublishing.getValue("version")

        println("############ gradle-integration version: $version")

        copyDirectory(Path("../.."), projectPath)
        val settingsKts = projectPath.resolve("settings.gradle.kts")

        settingsKts.readText()
            .mustReplace(Regex.fromLiteral("// mavenLocal()"), "mavenLocal()")
            .mustReplace(
                Regex(Regex.escape("id(\"org.jetbrains.amper.settings.plugin\").version(\"") +
                            "[\\w\\-.]+\"\\)"),
                "id(\"org.jetbrains.amper.settings.plugin\").version(\"$version\")"
            )
            .let { settingsKts.writeText(it) }

        // when
        val gradleHome = TestUtil.sharedTestCaches.resolve("gradleHome")
            .also { it.createDirectories() }
        val projectConnector = GradleConnector.newConnector()
            .useGradleUserHomeDir(gradleHome.toFile())
            .forProjectDirectory(projectPath.toFile())
            .connect()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        projectConnector.newBuild()
            .withArguments(
                //                "-Dorg.gradle.jvmargs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
                // --no-build-cache to actually build stuff instead of getting it from cache since cache is shared between runs
                "assemble", "--stacktrace", "--no-build-cache", "-PinBootstrapMode=true")
            .setStandardOutput(TeeOutputStream(System.out, stdout))
            .setStandardError(TeeOutputStream(System.err, stderr))
            .run()
        val output = (stdout.toByteArray().decodeToString() + "\n" + stderr.toByteArray().decodeToString()).replace("\r", "")

        // then
        assertTrue { output.contains("BUILD SUCCESSFUL") }
    }

    private fun String.mustReplace(regex: Regex, replacement: String): String {
        val s = replace(regex, replacement)
        if (this == s) {
            error("No replacements were made while replacing '${regex.pattern}' with '$replacement':\n" + this)
        }
        return s
    }
}

private fun copyDirectory(source: Path, target: Path) {
    var lastException: Throwable? = null

    for (i in 1..20) {
        // collecting the entire Amper project may fail,
        // e.g., .attach_pidPID may vanish while copying the project
        // since processes are run from Amper folders.
        // so far, it's our last, best hope for peace
        val filesList = try {
            collectFilesToCopy(source)
        } catch (t: Throwable) {
            // retry
            lastException = t
            Thread.sleep(50)
            continue
        }

        for (sourceFile in filesList) {
            check(!sourceFile.name.contains(".attach_pid")) {
                "Unable to copy .attach_pid* files, they'll vanish. Please exclude them"
            }

            val relativeName = sourceFile.relativeTo(source)
            val targetFile = target.resolve(relativeName)
            Files.createDirectories(targetFile.parent)
            Files.copy(sourceFile, targetFile)
        }

        return
    }

    throw lastException!!
}

private fun collectFilesToCopy(source: Path): List<Path> {
    val result = mutableListOf<Path>()
    Files.walkFileTree(source, object : FileVisitor<Path> {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (dir.name == "build" || dir.name == ".gradle" || dir.name == ".git" || dir.name == "shared test caches") {
                return FileVisitResult.SKIP_SUBTREE
            }

            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            // may vanish
            if (!file.name.contains(".attach_pid")) {
                result.add(file)
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            throw exc
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            return FileVisitResult.CONTINUE
        }
    })
    return result
}
