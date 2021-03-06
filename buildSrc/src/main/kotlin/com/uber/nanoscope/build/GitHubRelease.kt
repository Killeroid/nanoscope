/**
 * Copyright (c) 2018 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.nanoscope.build

import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.httpDelete
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.fuel.moshi.responseObject
import com.squareup.moshi.Moshi
import org.gradle.api.InvalidUserDataException
import java.io.File
import java.security.MessageDigest
import javax.annotation.CheckReturnValue
import javax.xml.bind.DatatypeConverter

private val GITHUB_TOKEN: String? = System.getenv("GITHUB_ACCESS_TOKEN")

private data class GitHubRelease(val downloadUrl: String, val bytes: ByteArray)
private data class Version(val major: Int, val minor: Int, val patch: Int) {

    override fun toString(): String {
        return "$major.$minor.$patch"
    }
}

private fun Process.assertSuccess() {
    if (waitFor() != 0) {
        throw RuntimeException("Command failed.")
    }
}

@CheckReturnValue
private fun String.exec(): Process {
    println(this)
    return ProcessBuilder()
            .command(this.split(" ").map { it.replace('`', ' ') })
            .start()
}

private fun Request.authGitHub(): Request {
    return this.header("Authorization" to "token $GITHUB_TOKEN")
}

private fun Request.json(): Map<String, Any> {
    return try {
        responseObject<Map<String, Any>>()
                .third
                .get()
    } catch (e: Exception) {
        throw RuntimeException("$url: ${e.message}")
    }
}

private class GitHubPublisher(
        private val distZip: File,
        private val version: Version) {

    fun publish(): GitHubRelease {
        val commitHash = getCommitHash()
        val bodyMap = mapOf(
                "tag_name" to version.toString(),
                "target_commitish" to commitHash,
                "name" to version.toString(),
                "body" to "$version release of Nanoscope",
                "draft" to false,
                "prerelease" to (version.major == 0))
        val body = Moshi.Builder().build().adapter(Map::class.java).toJson(bodyMap)
        val result = "https://api.github.com/repos/uber/nanoscope/releases"
                .httpPost()
                .authGitHub()
                .body(body)
                .json()

        val zipName = "nanoscope-$version.zip"
        val uploadUrl = result["upload_url"].toString().replace("{?name,label}", "?name=$zipName")
        val bytes = distZip.readBytes()
        val response = uploadUrl.httpPost()
                .authGitHub()
                .header("Content-Type" to "application/zip")
                .body(bytes)
                .json()

        val downloadUrl = response["browser_download_url"].toString()
        return GitHubRelease(downloadUrl, bytes)
    }

    private fun getCommitHash(): String {
        val proc = "git rev-parse --verify HEAD".exec()
        val text = proc.inputStream.bufferedReader().readText()
        return text.trim()
    }
}

private class HomebrewRepo private constructor(private val dir: File) {

    private val formulaFile = File(dir, "nanoscope.rb")

    fun ensureClean() {
        val repoPath = dir.absolutePath
        val gitUrl = "git@github.com:uber/homebrew-nanoscope.git"
        if (!dir.exists()) {
            dir.mkdirs()
            "clone $gitUrl $repoPath".gitExec().assertSuccess()
        }

        "fetch --all".gitExec().assertSuccess()
        "reset --hard origin/master".gitExec().assertSuccess()
        "checkout master".gitExec().assertSuccess()
        "pull".gitExec().assertSuccess()
    }

    fun update(version: Version, url: String, sha256: String) {
        var formula = readFormula()
        fun setProperty(name: String, value: Any) {
            formula = formula.replaceFirst(Regex("""$name ".*""""), """$name "$value"""")
        }
        setProperty("version", version)
        setProperty("url", url)
        setProperty("sha256", sha256)
        formulaFile.writeText(formula)
    }

    fun commit(message: String) {
        """commit -am ${message.replace(' ', '`')}""".gitExec().assertSuccess()
    }

    fun push() {
        "push".gitExec().assertSuccess()
    }

    fun readVersion(): Version {
        val formula = readFormula()
        val versionString = Regex("""version "(.*)"""").find(formula)!!.groupValues[1]
        val parts = versionString.split('.').map { it.toInt() }
        return Version(parts[0], parts[1], parts[2])
    }

    @CheckReturnValue
    private fun String.gitExec(): Process {
        return "git -C ${dir.absolutePath} $this".exec()
    }

    private fun readFormula(): String {
        return formulaFile.readText()
    }

    companion object {

        fun init(): HomebrewRepo {
            val homeDir = System.getProperty("user.home")
            val repoDir = File(homeDir, ".nanoscope/homebrew-cache")
            return HomebrewRepo(repoDir)
        }
    }
}

enum class IncrementType {
    MAJOR, MINOR, PATCH
}

private fun Version.increment(incrementType: IncrementType): Version {
    return when (incrementType) {
        IncrementType.MAJOR -> copy(major = major + 1, minor = 0, patch = 0)
        IncrementType.MINOR -> copy(minor = minor + 1, patch = 0)
        IncrementType.PATCH -> copy(patch = patch + 1)
    }
}

class Nanoscope private constructor() {

    companion object {

        @JvmStatic
        fun release(distZip: File, incrementType: IncrementType) {
            ensureGitHubToken()
            val homebrewRepo = HomebrewRepo.init()
            homebrewRepo.ensureClean()

            val version = homebrewRepo.readVersion().increment(incrementType)

            val release = GitHubPublisher(distZip, version).publish()
            val sha256 = DatatypeConverter.printHexBinary(MessageDigest.getInstance("SHA-256").digest(release.bytes)).toLowerCase()
            val downloadUrl = release.downloadUrl

            homebrewRepo.update(version, downloadUrl, sha256)
            homebrewRepo.commit("Update version to $version.")
            homebrewRepo.push()
        }

        @JvmStatic
        fun deleteReleaseDrafts() {
            ensureGitHubToken()
            "https://api.github.com/repos/uber/nanoscope/releases"
                    .httpGet()
                    .authGitHub()
                    .responseObject<List<Map<String, Any>>>().third.get()
                    .filter { it["draft"] == true }
                    .forEach {
                        it["url"].toString()
                                .httpDelete()
                                .authGitHub()
                                .response()
                    }
        }

        private fun ensureGitHubToken() {
            if (GITHUB_TOKEN.isNullOrEmpty()) {
                throw InvalidUserDataException("GITHUB_ACCESS_TOKEN not set.")
            }
        }
    }
}