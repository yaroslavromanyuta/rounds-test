package com.rounds.imageloader

import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Asserts the *compiled* [ImageLoader] declaration carries the thread contract, not just its KDoc.
 *
 * The contract only does its job if a consumer's Android Lint can read it, and lint reads the class
 * file. AndroidX thread annotations have `CLASS` retention, so ordinary reflection cannot see them —
 * `ImageLoader::class.java.methods.first().annotations` is empty however many annotations are on the
 * source. This test therefore reads the class file itself and looks for the annotation descriptors
 * in each method's `RuntimeInvisibleAnnotations` attribute, which is exactly what lint consumes.
 *
 * Methods are looked up by name *and* descriptor, so this doubles as the guard that the public
 * signatures stay binary compatible: renaming a parameter type or turning an overload into a
 * defaulted parameter changes the descriptor and fails here.
 */
class PublicApiThreadContractTest {

    @Test
    fun loadOverloadsDeclareTheMainThreadContract() {
        val imageLoader = ClassFile.of(ImageLoader::class.java)

        assertEquals(
            listOf(MAIN_THREAD),
            imageLoader.annotationsOn("load", "(Ljava/lang/String;ILandroid/widget/ImageView;)V"),
        )
        assertEquals(
            listOf(MAIN_THREAD),
            imageLoader.annotationsOn("load", "(Ljava/lang/String;Landroid/widget/ImageView;)V"),
        )
    }

    @Test
    fun clearDeclaresTheMainThreadContract() {
        val imageLoader = ClassFile.of(ImageLoader::class.java)

        assertEquals(
            listOf(MAIN_THREAD),
            imageLoader.annotationsOn("clear", "(Landroid/widget/ImageView;)V"),
        )
    }

    @Test
    fun cacheInvalidationDeclaresTheAnyThreadContract() {
        val imageLoader = ClassFile.of(ImageLoader::class.java)

        assertEquals(listOf(ANY_THREAD), imageLoader.annotationsOn("clearCache", "()V"))
        assertEquals(
            listOf(ANY_THREAD),
            imageLoader.annotationsOn("invalidate", "(Ljava/lang/String;)V"),
        )
    }

    @Test
    fun theApiExposesExactlyThesePublicMethods() {
        val imageLoader = ClassFile.of(ImageLoader::class.java)

        // A new public method without a thread contract would otherwise slip past the assertions
        // above, which can only check the methods they name. `create` is the @JvmStatic factory the
        // interface companion emits onto the interface itself; `<clinit>` initialises NO_PLACEHOLDER
        // and the companion field and is not API.
        assertEquals(
            setOf(
                "load(Ljava/lang/String;ILandroid/widget/ImageView;)V",
                "load(Ljava/lang/String;Landroid/widget/ImageView;)V",
                "clear(Landroid/widget/ImageView;)V",
                "clearCache()V",
                "invalidate(Ljava/lang/String;)V",
                "create(Landroid/content/Context;)Lcom/rounds/imageloader/ImageLoader;",
            ),
            imageLoader.methods
                .filterNot { it.name == "<clinit>" }
                .map { it.name + it.descriptor }
                .toSet(),
        )
    }

    private companion object {

        const val MAIN_THREAD = "Landroidx/annotation/MainThread;"
        const val ANY_THREAD = "Landroidx/annotation/AnyThread;"
    }
}

/**
 * The few paragraphs of JVMS §4 needed to read annotations off a method, so that this assertion
 * needs no bytecode library on the test classpath.
 */
private class ClassFile(val methods: List<Method>) {

    class Method(
        val name: String,
        val descriptor: String,
        val annotations: List<String>,
    )

    /** Annotation descriptors on [name][descriptor], failing rather than returning null if absent. */
    fun annotationsOn(name: String, descriptor: String): List<String> {
        val method = methods.firstOrNull { it.name == name && it.descriptor == descriptor }
        assertNotNull("no method $name$descriptor in the compiled class", method)
        return method!!.annotations
    }

    companion object {

        private const val RUNTIME_INVISIBLE_ANNOTATIONS = "RuntimeInvisibleAnnotations"

        fun of(type: Class<*>): ClassFile {
            val resource = type.name.replace('.', '/') + ".class"
            val bytes = checkNotNull(type.classLoader.getResourceAsStream(resource)) {
                "$resource is not on the test classpath"
            }.use { it.readBytes() }
            return read(bytes)
        }

        private fun read(bytes: ByteArray): ClassFile {
            val input = DataInputStream(bytes.inputStream())
            check(input.readInt() == -0x35014542) { "not a class file" }  // 0xCAFEBABE
            input.readUnsignedShort()  // minor version
            input.readUnsignedShort()  // major version

            val constants = readConstantPool(input)

            input.readUnsignedShort()  // access flags
            input.readUnsignedShort()  // this class
            input.readUnsignedShort()  // super class
            input.skipFully(2 * input.readUnsignedShort())  // interfaces

            repeat(input.readUnsignedShort()) { readMember(input, constants) }  // fields
            val methods = List(input.readUnsignedShort()) { readMember(input, constants) }
            return ClassFile(methods)
        }

        /**
         * Constant pool entries are indexed from 1, and `long`/`double` occupy two indices each —
         * hence the manual cursor rather than a list built by appending.
         */
        private fun readConstantPool(input: DataInputStream): Map<Int, String> {
            val count = input.readUnsignedShort()
            val utf8 = mutableMapOf<Int, String>()
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    7, 8, 16, 19, 20 -> input.skipFully(2)
                    15 -> input.skipFully(3)
                    3, 4, 9, 10, 11, 12, 17, 18 -> input.skipFully(4)
                    5, 6 -> {
                        input.skipFully(8)
                        index++  // takes two pool slots
                    }
                    else -> error("unsupported constant pool tag $tag at $index")
                }
                index++
            }
            return utf8
        }

        private fun readMember(input: DataInputStream, constants: Map<Int, String>): Method {
            input.readUnsignedShort()  // access flags
            val name = constants.getValue(input.readUnsignedShort())
            val descriptor = constants.getValue(input.readUnsignedShort())
            val annotations = mutableListOf<String>()
            repeat(input.readUnsignedShort()) {
                val attributeName = constants.getValue(input.readUnsignedShort())
                val body = ByteArray(input.readInt())
                input.readFully(body)
                if (attributeName == RUNTIME_INVISIBLE_ANNOTATIONS) {
                    annotations += readAnnotations(body, constants)
                }
            }
            return Method(name, descriptor, annotations)
        }

        private fun readAnnotations(body: ByteArray, constants: Map<Int, String>): List<String> {
            val input = DataInputStream(body.inputStream())
            return List(input.readUnsignedShort()) { readAnnotation(input, constants) }
        }

        private fun readAnnotation(input: DataInputStream, constants: Map<Int, String>): String {
            val type = constants.getValue(input.readUnsignedShort())
            // Thread annotations are markers, but skipping element values properly keeps this
            // reader honest if a future annotation on the API does carry some.
            repeat(input.readUnsignedShort()) {
                input.skipFully(2)  // element name index
                skipElementValue(input, constants)
            }
            return type
        }

        private fun skipElementValue(input: DataInputStream, constants: Map<Int, String>) {
            when (val tag = input.readUnsignedByte().toChar()) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.skipFully(2)
                'e' -> input.skipFully(4)
                '@' -> readAnnotation(input, constants)
                '[' -> repeat(input.readUnsignedShort()) { skipElementValue(input, constants) }
                else -> error("unsupported element value tag $tag")
            }
        }

        private fun DataInputStream.skipFully(count: Int) {
            skipBytes(count).let { check(it == count) { "truncated class file" } }
        }
    }
}
