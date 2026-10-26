package com.mmbesar.kancard.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Kanboard answers are not consistent: the same field can arrive as the text "5"
// or the number 5 depending on the Kanboard version/database. These two
// serializers accept both, and treat null/missing as 0 or "".

/** Reads "5", 5, 5.0 or null as a Long. */
object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return decoder.decodeLong()
        val primitive = element as? JsonPrimitive ?: return 0L
        if (primitive is JsonNull) return 0L
        return primitive.content.toLongOrNull()
            ?: primitive.content.toDoubleOrNull()?.toLong()
            ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/** Reads text, numbers or null as a String (null becomes ""). */
object FlexibleStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: return decoder.decodeString()
        val primitive = element as? JsonPrimitive ?: return ""
        if (primitive is JsonNull) return ""
        return primitive.content
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** One project as Kanboard returns it. Only the fields we use. */
@Serializable
data class ProjectDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long = 0,
    @Serializable(with = FlexibleStringSerializer::class) val name: String = "",
    @SerialName("is_active")
    @Serializable(with = FlexibleLongSerializer::class) val isActive: Long = 1,
)

/** One board column. */
@Serializable
data class ColumnDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long = 0,
    @Serializable(with = FlexibleStringSerializer::class) val title: String = "",
    @Serializable(with = FlexibleLongSerializer::class) val position: Long = 0,
    @SerialName("project_id")
    @Serializable(with = FlexibleLongSerializer::class) val projectId: Long = 0,
)

/** One task. Dates arrive as epoch seconds (0 = no date). */
@Serializable
data class TaskDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long = 0,
    @SerialName("project_id")
    @Serializable(with = FlexibleLongSerializer::class) val projectId: Long = 0,
    @SerialName("column_id")
    @Serializable(with = FlexibleLongSerializer::class) val columnId: Long = 0,
    @SerialName("swimlane_id")
    @Serializable(with = FlexibleLongSerializer::class) val swimlaneId: Long = 0,
    @Serializable(with = FlexibleStringSerializer::class) val title: String = "",
    @Serializable(with = FlexibleStringSerializer::class) val description: String = "",
    @Serializable(with = FlexibleLongSerializer::class) val position: Long = 0,
    @SerialName("date_due")
    @Serializable(with = FlexibleLongSerializer::class) val dateDue: Long = 0,
    @SerialName("is_active")
    @Serializable(with = FlexibleLongSerializer::class) val isActive: Long = 1,
    @SerialName("color_id")
    @Serializable(with = FlexibleStringSerializer::class) val colorId: String = "",
    @Serializable(with = FlexibleLongSerializer::class) val priority: Long = 0,
)

/** One subtask. status: 0 = to do, 1 = in progress, 2 = done. */
@Serializable
data class SubtaskDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long = 0,
    @Serializable(with = FlexibleStringSerializer::class) val title: String = "",
    @Serializable(with = FlexibleLongSerializer::class) val status: Long = 0,
    @Serializable(with = FlexibleLongSerializer::class) val position: Long = 0,
)

/** One comment. [name] is the author's full name (may be empty), [username] the login name. */
@Serializable
data class CommentDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long = 0,
    @SerialName("date_creation")
    @Serializable(with = FlexibleLongSerializer::class) val dateCreation: Long = 0,
    @Serializable(with = FlexibleStringSerializer::class) val comment: String = "",
    @Serializable(with = FlexibleStringSerializer::class) val name: String = "",
    @Serializable(with = FlexibleStringSerializer::class) val username: String = "",
)
