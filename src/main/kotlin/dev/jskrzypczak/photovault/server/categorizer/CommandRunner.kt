package dev.jskrzypczak.photovault.server.categorizer

import java.io.File

data class CommandResult(val exitCode: Int, val output: String)

fun interface CommandRunner {
    /** Executes [command] via `bash -c` in [workdir], returns exit code and combined stdout+stderr. */
    fun run(command: String, workdir: String): CommandResult
}

val DefaultCommandRunner = CommandRunner { command, workdir ->
    val process = ProcessBuilder("bash", "-c", command)
        .directory(File(workdir))
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    print(output)
    CommandResult(process.waitFor(), output)
}
