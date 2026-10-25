package io.smartdata.foundation.compiler

import java.nio.file.Path
import kotlin.io.path.absolutePathString

fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "Usage: domain-compiler <pack.yaml> [output-directory] [previous-domain-model.json]"
    }
    val source = Path.of(args[0]).toAbsolutePath().normalize()
    val output = args.getOrNull(1)?.let(Path::of)?.toAbsolutePath()?.normalize()
        ?: source.parent.resolve("build/compiled/${source.fileName.toString().substringBeforeLast('.')}")
    val previous = args.getOrNull(2)?.let(Path::of)?.toAbsolutePath()?.normalize()
    val result = DomainPackCompiler().compile(source, output, previous)
    println("Compiled ${result.ir.packId}@${result.ir.packVersion} to ${output.absolutePathString()}")
    result.files.forEach { println(" - $it") }
}
