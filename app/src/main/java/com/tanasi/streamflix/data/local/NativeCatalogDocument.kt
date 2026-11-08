package com.tanasi.streamflix.data.local

import androidx.annotation.Keep
import com.chaquo.python.PyObject

/** Bridge to Python's URL resolver while document state is owned by Kotlin. */
@Keep
class NativeCatalogDocumentFactory {
    fun create(page: String, urljoin: PyObject): NativeCatalogDocument = NativeCatalogDocument(page, urljoin)
}

@Keep
class NativeCatalogDocument internal constructor(page: String, urljoin: PyObject) {
    private val core = NativeCatalogDocumentCore(page) { base, ref -> urljoin.call(base, ref).toString() }
    fun start(tag: String, attrsJson: String): Boolean = core.start(tag, attrsJson)
    fun data(value: String): Boolean = core.data(value)
    fun end(tag: String) = core.end(tag)
    fun snapshot(): String = core.snapshot()
}
