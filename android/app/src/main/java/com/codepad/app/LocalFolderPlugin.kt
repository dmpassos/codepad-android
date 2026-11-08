package com.codepad.app

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Pasta local do aparelho para o Workspace do CodePad.
 *
 * Usa o Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE): o utilizador escolhe uma
 * pasta, o Android dá à app acesso persistente SÓ a essa pasta (sem permissões de
 * armazenamento no manifesto). O lado JS (native-bridge.js) transforma estas chamadas
 * na File System Access API, por isso o HTML do CodePad não precisa de mudar.
 *
 * Todos os métodos recebem:
 *   tree  → URI da árvore devolvido por pickDirectory()
 *   path  → caminho relativo à raiz, com "/" ("" = a própria raiz)
 *
 * Códigos de erro: CANCELLED, NOT_FOUND, TYPE_MISMATCH, NOT_EMPTY, PERMISSION, IO
 */
@CapacitorPlugin(name = "LocalFolder")
class LocalFolderPlugin : Plugin() {

    private data class Meta(
        val docId: String,
        val name: String,
        val isDir: Boolean,
        val lastModified: Long,
        val size: Long,
    )

    /** "tree|caminho" -> documentId (evita listar pastas a cada acesso) */
    private val idCache = ConcurrentHashMap<String, String>()

    private val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    // ───────────────────────────── escolher pasta ─────────────────────────────

    @PluginMethod
    fun pickDirectory(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            val initial = call.getString("initialUri")
            if (!initial.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(initial))
            }
        }
        startActivityForResult(call, intent, "onPickResult")
    }

    @ActivityCallback
    private fun onPickResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Seleção de pasta cancelada", "CANCELLED")
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            evict(uri.toString())
            val root = statDoc(uri, DocumentsContract.getTreeDocumentId(uri))
            call.resolve(
                JSObject()
                    .put("tree", uri.toString())
                    .put("name", root?.name ?: "pasta"),
            )
        } catch (e: Exception) {
            call.reject("Não foi possível guardar a permissão da pasta: ${e.message}", "PERMISSION")
        }
    }

    @PluginMethod
    fun hasPermission(call: PluginCall) {
        guarded(call) {
            val tree = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val granted = context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == tree && it.isReadPermission && it.isWritePermission
            }
            call.resolve(JSObject().put("granted", granted))
        }
    }

    // ───────────────────────────── leitura ─────────────────────────────

    @PluginMethod
    fun stat(call: PluginCall) {
        guarded(call) {
            val tree = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val meta = resolve(tree, call.getString("path"))
            val out = JSObject().put("exists", meta != null)
            if (meta != null) {
                out.put("kind", if (meta.isDir) "directory" else "file")
                out.put("lastModified", meta.lastModified)
                out.put("size", meta.size)
            }
            call.resolve(out)
        }
    }

    @PluginMethod
    fun list(call: PluginCall) {
        guarded(call) {
            val treeStr = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val path = norm(call.getString("path"))
            val dir = resolve(treeStr, path) ?: return call.reject("Pasta não encontrada", "NOT_FOUND")
            if (!dir.isDir) return call.reject("Isto não é uma pasta", "TYPE_MISMATCH")

            val entries = JSArray()
            for (child in listChildren(Uri.parse(treeStr), dir.docId)) {
                idCache[cacheKey(treeStr, join(path, child.name))] = child.docId
                entries.put(
                    JSObject()
                        .put("name", child.name)
                        .put("kind", if (child.isDir) "directory" else "file")
                        .put("lastModified", child.lastModified)
                        .put("size", child.size),
                )
            }
            call.resolve(JSObject().put("entries", entries))
        }
    }

    @PluginMethod
    fun readText(call: PluginCall) {
        guarded(call) {
            val treeStr = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val meta = resolve(treeStr, call.getString("path"))
                ?: return call.reject("Ficheiro não encontrado", "NOT_FOUND")
            if (meta.isDir) return call.reject("Isto é uma pasta, não um ficheiro", "TYPE_MISMATCH")

            val bytes = context.contentResolver
                .openInputStream(docUri(Uri.parse(treeStr), meta.docId))
                ?.use { it.readBytes() }
                ?: throw FileNotFoundException("Não foi possível abrir o ficheiro")
            var text = String(bytes, Charsets.UTF_8)
            if (text.startsWith("\uFEFF")) text = text.substring(1) // Blob.text() também remove o BOM
            call.resolve(
                JSObject()
                    .put("text", text)
                    .put("lastModified", meta.lastModified)
                    .put("size", meta.size),
            )
        }
    }

    // ───────────────────────────── escrita ─────────────────────────────

    @PluginMethod
    fun writeText(call: PluginCall) {
        guarded(call) {
            val treeStr = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val path = norm(call.getString("path"))
            if (path.isEmpty()) return call.reject("Caminho em falta", "IO")
            val text = call.getString("text") ?: ""
            val create = call.getBoolean("create", true) ?: true

            var meta = resolve(treeStr, path)
            if (meta == null) {
                if (!create) return call.reject("Ficheiro não encontrado", "NOT_FOUND")
                meta = createChild(treeStr, path, isDir = false)
            }
            if (meta.isDir) return call.reject("Isto é uma pasta, não um ficheiro", "TYPE_MISMATCH")

            val tree = Uri.parse(treeStr)
            // "wt" = escrever e truncar (substitui o conteúdo anterior)
            context.contentResolver.openOutputStream(docUri(tree, meta.docId), "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: throw IOException("Não foi possível abrir o ficheiro para escrita")

            val after = statDoc(tree, meta.docId)
            call.resolve(
                JSObject()
                    .put("lastModified", after?.lastModified ?: System.currentTimeMillis())
                    .put("size", after?.size ?: text.length.toLong()),
            )
        }
    }

    @PluginMethod
    fun mkdir(call: PluginCall) {
        guarded(call) {
            val treeStr = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val path = norm(call.getString("path"))
            if (path.isEmpty()) return call.reject("Caminho em falta", "IO")
            val existing = resolve(treeStr, path)
            if (existing != null && !existing.isDir) {
                return call.reject("Já existe um ficheiro com esse nome", "TYPE_MISMATCH")
            }
            if (existing == null) createChild(treeStr, path, isDir = true)
            call.resolve()
        }
    }

    @PluginMethod
    fun remove(call: PluginCall) {
        guarded(call) {
            val treeStr = call.getString("tree") ?: return call.reject("Falta o parâmetro tree")
            val path = norm(call.getString("path"))
            if (path.isEmpty()) return call.reject("Não é possível remover a pasta raiz", "IO")
            val recursive = call.getBoolean("recursive", false) ?: false

            val meta = resolve(treeStr, path) ?: return call.reject("Não encontrado", "NOT_FOUND")
            val tree = Uri.parse(treeStr)
            if (meta.isDir && !recursive && listChildren(tree, meta.docId).isNotEmpty()) {
                return call.reject("A pasta não está vazia", "NOT_EMPTY")
            }
            if (!DocumentsContract.deleteDocument(context.contentResolver, docUri(tree, meta.docId))) {
                return call.reject("O Android recusou apagar \"$path\"", "IO")
            }
            evict(cacheKey(treeStr, path))
            call.resolve()
        }
    }

    // ───────────────────────────── internos ─────────────────────────────

    /** Executa [block] e converte as exceções mais comuns em erros com código. */
    private inline fun guarded(call: PluginCall, block: () -> Unit) {
        try {
            block()
        } catch (e: FileNotFoundException) {
            call.reject(e.message ?: "Não encontrado", "NOT_FOUND")
        } catch (e: SecurityException) {
            call.reject("Sem permissão para esta pasta. Mapeie-a de novo.", "PERMISSION")
        } catch (e: Exception) {
            call.reject(e.message ?: e.javaClass.simpleName, "IO")
        }
    }

    private fun splitPath(path: String?): List<String> =
        (path ?: "").split('/').filter { it.isNotEmpty() }

    private fun norm(path: String?): String = splitPath(path).joinToString("/")

    private fun join(base: String, name: String): String = if (base.isEmpty()) name else "$base/$name"

    private fun cacheKey(tree: String, path: String) = "$tree|$path"

    /** Apaga do cache a entrada [key] e tudo o que estiver por baixo dela. */
    private fun evict(key: String) {
        val iter = idCache.keys.iterator()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k == key || k.startsWith("$key/") || k.startsWith("$key|")) iter.remove()
        }
    }

    private fun docUri(tree: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun Cursor.toMeta() = Meta(
        docId = getString(0) ?: "",
        name = getString(1) ?: "",
        isDir = getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
        lastModified = if (isNull(3)) 0L else getLong(3),
        size = if (isNull(4)) 0L else getLong(4),
    )

    private fun statDoc(tree: Uri, docId: String): Meta? = try {
        context.contentResolver.query(docUri(tree, docId), columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.toMeta() else null
        }
    } catch (e: Exception) {
        null
    }

    private fun listChildren(tree: Uri, parentId: String): List<Meta> {
        val out = ArrayList<Meta>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(c.toMeta())
        }
        return out
    }

    /** Resolve "a/b/c.h" a partir da raiz da árvore; null se não existir. */
    private fun resolve(treeStr: String, rawPath: String?): Meta? {
        val tree = Uri.parse(treeStr)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val parts = splitPath(rawPath)
        if (parts.isEmpty()) return statDoc(tree, rootId)

        var currentId = rootId
        var meta: Meta? = null
        var prefix = ""
        for ((index, part) in parts.withIndex()) {
            prefix = join(prefix, part)
            val key = cacheKey(treeStr, prefix)

            meta = null
            val cachedId = idCache[key]
            if (cachedId != null) {
                val s = statDoc(tree, cachedId)
                if (s != null && s.name == part) meta = s else idCache.remove(key)
            }
            if (meta == null) {
                val found = listChildren(tree, currentId).firstOrNull { it.name == part } ?: return null
                idCache[key] = found.docId
                meta = found
            }
            if (index < parts.size - 1 && !meta.isDir) return null
            currentId = meta.docId
        }
        return meta
    }

    /** Cria um ficheiro/pasta vazio. A pasta-mãe tem de existir. */
    private fun createChild(treeStr: String, path: String, isDir: Boolean): Meta {
        val tree = Uri.parse(treeStr)
        val parts = splitPath(path)
        val name = parts.last()
        val parentPath = parts.dropLast(1).joinToString("/")
        val parent = resolve(treeStr, parentPath)
            ?: throw FileNotFoundException("A pasta \"$parentPath\" não existe")
        if (!parent.isDir) throw IOException("\"$parentPath\" não é uma pasta")

        // octet-stream: o Android não acrescenta extensões (evita "main.c" virar "main.c.txt")
        val mime = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            docUri(tree, parent.docId),
            mime,
            name,
        ) ?: throw IOException("O Android recusou criar \"$name\"")

        val docId = DocumentsContract.getDocumentId(created)
        val meta = statDoc(tree, docId) ?: throw IOException("Criado \"$name\", mas não consegui lê-lo")
        if (meta.name != name) {
            // o fornecedor renomeou-o (ex.: "main (1).c"); desfazer em vez de deixar lixo
            try {
                DocumentsContract.deleteDocument(context.contentResolver, created)
            } catch (e: Exception) {
                // sem importância: já vamos falhar com a mensagem abaixo
            }
            throw IOException("O fornecedor de armazenamento alterou o nome para \"${meta.name}\"")
        }
        idCache[cacheKey(treeStr, norm(path))] = docId
        return meta
    }
}
