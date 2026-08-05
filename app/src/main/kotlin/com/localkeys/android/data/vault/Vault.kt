package com.localkeys.android.data.vault

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema do vault (dentro do blob cifrado) — espelho exato do `types.ts` do
 * desktop. O back-end trata o blob como bytes opacos; a estrutura vive aqui.
 * `version` permite migrar depois.
 */
data class Vault(
    val version: Int,
    val folders: List<Folder>,
    val items: List<Item>,
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("version", version)
        val foldersArr = JSONArray()
        folders.forEach { foldersArr.put(it.toJson()) }
        json.put("folders", foldersArr)
        val itemsArr = JSONArray()
        items.forEach { itemsArr.put(it.toJson()) }
        json.put("items", itemsArr)
        return json.toString()
    }

    companion object {
        fun empty(): Vault = Vault(version = 1, folders = emptyList(), items = emptyList())

        fun parse(json: String): Vault {
            val obj = JSONObject(json)
            val version = obj.optInt("version", 1)
            val folders = obj.optJSONArray("folders")?.let { arr ->
                (0 until arr.length()).map { Folder.parse(arr.getJSONObject(it)) }
            } ?: emptyList()
            val items = obj.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).map { Item.parse(arr.getJSONObject(it)) }
            } ?: emptyList()
            return Vault(version, folders, items)
        }
    }
}

data class Folder(
    val id: String,
    val name: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }

    companion object {
        fun parse(json: JSONObject): Folder = Folder(
            id = json.getString("id"),
            name = json.getString("name"),
        )
    }
}

enum class ItemKind(val wire: String) {
    LOGIN("login"),
    NOTE("note"),
    CARD("card"),
    IDENTITY("identity");

    companion object {
        fun fromWire(value: String): ItemKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("tipo de item desconhecido: $value")
    }
}

data class Item(
    val id: String,
    val kind: ItemKind,
    val name: String,
    val favorite: Boolean,
    val folderId: String?,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val login: Login?,
    val card: Card?,
    val identity: Identity?,
    val passwordHistory: List<PasswordHistoryEntry>,
    val customFields: List<CustomField>,
    val attachments: List<Attachment>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.wire)
        put("name", name)
        put("favorite", favorite)
        if (folderId != null) put("folderId", folderId) else put("folderId", JSONObject.NULL)
        put("notes", notes)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (deletedAt != null) put("deletedAt", deletedAt) else put("deletedAt", JSONObject.NULL)
        login?.let { put("login", it.toJson()) }
        card?.let { put("card", it.toJson()) }
        identity?.let { put("identity", it.toJson()) }
        if (passwordHistory.isNotEmpty()) put("passwordHistory", JSONArray().apply { passwordHistory.forEach { put(it.toJson()) } })
        if (customFields.isNotEmpty()) put("customFields", JSONArray().apply { customFields.forEach { put(it.toJson()) } })
        if (attachments.isNotEmpty()) put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
    }

    companion object {
        fun parse(json: JSONObject): Item = Item(
            id = json.getString("id"),
            kind = ItemKind.fromWire(json.getString("kind")),
            name = json.optString("name", ""),
            favorite = json.optBoolean("favorite", false),
            folderId = if (json.isNull("folderId")) null else json.optString("folderId", ""),
            notes = json.optString("notes", ""),
            createdAt = json.optLong("createdAt", 0),
            updatedAt = json.optLong("updatedAt", 0),
            deletedAt = if (json.isNull("deletedAt")) null else json.optLong("deletedAt"),
            login = if (json.isNull("login")) null else json.optJSONObject("login")?.let { Login.parse(it) },
            card = if (json.isNull("card")) null else json.optJSONObject("card")?.let { Card.parse(it) },
            identity = if (json.isNull("identity")) null else json.optJSONObject("identity")?.let { Identity.parse(it) },
            passwordHistory = json.optJSONArray("passwordHistory")?.let { arr ->
                (0 until arr.length()).map { PasswordHistoryEntry.parse(arr.getJSONObject(it)) }
            } ?: emptyList(),
            customFields = json.optJSONArray("customFields")?.let { arr ->
                (0 until arr.length()).map { CustomField.parse(arr.getJSONObject(it)) }
            } ?: emptyList(),
            attachments = json.optJSONArray("attachments")?.let { arr ->
                (0 until arr.length()).map { Attachment.parse(arr.getJSONObject(it)) }
            } ?: emptyList(),
        )
    }
}

data class Login(
    val username: String,
    val password: String,
    val uris: List<String>,
    val totp: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("username", username)
        put("password", password)
        put("uris", JSONArray().apply { uris.forEach { put(it) } })
        put("totp", totp)
    }

    companion object {
        fun parse(json: JSONObject): Login = Login(
            username = json.optString("username", ""),
            password = json.optString("password", ""),
            uris = json.optJSONArray("uris")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            totp = json.optString("totp", ""),
        )
    }
}

data class Card(
    val cardholder: String,
    val brand: String,
    val number: String,
    val exp: String,
    val code: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cardholder", cardholder)
        put("brand", brand)
        put("number", number)
        put("exp", exp)
        put("code", code)
    }

    companion object {
        fun parse(json: JSONObject): Card = Card(
            cardholder = json.optString("cardholder", ""),
            brand = json.optString("brand", ""),
            number = json.optString("number", ""),
            exp = json.optString("exp", ""),
            code = json.optString("code", ""),
        )
    }
}

data class Identity(
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val address: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("firstName", firstName)
        put("lastName", lastName)
        put("email", email)
        put("phone", phone)
        put("address", address)
    }

    companion object {
        fun parse(json: JSONObject): Identity = Identity(
            firstName = json.optString("firstName", ""),
            lastName = json.optString("lastName", ""),
            email = json.optString("email", ""),
            phone = json.optString("phone", ""),
            address = json.optString("address", ""),
        )
    }
}

data class PasswordHistoryEntry(
    val password: String,
    val at: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("password", password)
        put("at", at)
    }

    companion object {
        fun parse(json: JSONObject): PasswordHistoryEntry = PasswordHistoryEntry(
            password = json.optString("password", ""),
            at = json.optLong("at", 0),
        )
    }
}

data class CustomField(
    val id: String,
    val name: String,
    val value: String,
    val hidden: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("value", value)
        put("hidden", hidden)
    }

    companion object {
        fun parse(json: JSONObject): CustomField = CustomField(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            value = json.optString("value", ""),
            hidden = json.optBoolean("hidden", false),
        )
    }
}

data class Attachment(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val dataB64: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("size", size)
        put("mime", mime)
        put("dataB64", dataB64)
    }

    companion object {
        fun parse(json: JSONObject): Attachment = Attachment(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            size = json.optLong("size", 0),
            mime = json.optString("mime", ""),
            dataB64 = json.optString("dataB64", ""),
        )
    }
}
