package com.example.applenotes.proto

import android.util.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "AppleNotesMerge"

/**
 * Best-effort decoder for the `MergeableDataEncrypted` field on Notes'
 * Attachment records (tables, image galleries, etc.). Format is Apple's
 * own CRDT graph-object proto, similar to the legacy NoteStoreProto's
 * topotext but more general.
 *
 * Schema (inferred from sampling a 2x2 table the user round-tripped through
 * iCloud.com):
 *
 *   top {
 *     int32 version = 1;
 *     Wrapper wrapper = 2 {
 *       int32 minSupportedVersion = 1;
 *       int32 actualVersion       = 2;
 *       ObjectData data            = 3;
 *     }
 *   }
 *
 *   ObjectData {
 *     repeated GraphObject objects   = 3;   // INDEXED 0..N — graph nodes
 *     repeated KeyItem      keys     = 4;   // string field names
 *     repeated TypeItem     types    = 5;   // string type ids (indexed)
 *     repeated bytes        uuids    = 6;   // 16-byte UUID array (indexed)
 *   }
 *
 *   GraphObject =oneof= {
 *     List       list       = 1;   // unclear, ignored
 *     Dictionary dict       = 6;   // map<obj_id, obj_id>
 *     String     string     = 10;  // NSString — f2 = current text
 *     CustomMap  customMap  = 13;  // typed map — ICTable, NSUUID, NSNumber, NSString
 *     OrderedSet orderedSet = 16;  // ordered array of obj refs
 *   }
 *
 *   CustomMap {
 *     int32 typeId           = 1;  // index into ObjectData.types
 *     repeated MapEntry e     = 3;
 *   }
 *
 *   MapEntry {
 *     int32  keyId  = 1;            // index into ObjectData.keys
 *     Value  value = 2;
 *   }
 *
 *   Value =oneof= {
 *     int32  unsignedInt   = 6;     // = either inline int OR an object_id ref
 *     bytes  stringValue   = 4;     // raw inline UTF-8 string (for short identity values)
 *   }
 *
 *   Dictionary { repeated DictEntry e = 1; }
 *   DictEntry { ValueRef key = 1; ValueRef val = 2; bytes meta = 3; }
 *   ValueRef { int32 unsignedInt = 6; }
 *
 *   OrderedSet { ListInfo info = 1; repeated ListNode nodes = 2; }
 *   ListNode  { Element f1; }
 *   Element   { int32 target = 1; int32 prev = 2; bytes meta = 3; }
 *
 *   String /NSString/ { bytes currentText = 2; ...op history... }
 *
 * For tables we walk:
 *   1. Find ICTable CustomMap (typeId references "com.apple.notes.ICTable")
 *   2. From ICTable, follow ref keys "crRows" / "crColumns" / "cellColumns"
 *   3. crRows / crColumns are OrderedSets of row/column NSUUID-wrapper refs
 *   4. cellColumns is Dict<colRef, Dict<rowRef, stringRef>>
 *   5. Resolve string refs via NSString.currentText
 */
@OptIn(ExperimentalEncodingApi::class)
object MergeableDataDecoder {

    /** A 2D table grid extracted from a Notes Attachment. */
    data class TableGrid(
        val rows: Int,
        val cols: Int,
        /** Cells in row-major order: cells[row][col]. May contain "" for empty. */
        val cells: List<List<String>>,
    )

    /** Top-level entry for the table case. Returns null if the bytes don't look like a Notes table. */
    fun decodeTableBase64(b64: String): TableGrid? = runCatching {
        val proto = Gzip.decompress(Base64.decode(b64))
        decodeTable(proto)
    }.onFailure { Log.w(TAG, "decodeTableBase64 failed: ${it.message}") }.getOrNull()

    fun decodeTable(proto: ByteArray): TableGrid? {
        val od = parseObjectData(proto) ?: return null
        return extractTable(od)
    }

    private data class ObjectData(
        val objects: List<List<ProtobufWire.Field>>,
        val keys: List<String>,
        val types: List<String>,
        val uuids: List<ByteArray>,
    )

    private fun parseObjectData(proto: ByteArray): ObjectData? = runCatching {
        val top = ProtobufWire.decode(proto)
        val wrapper = top.firstOrNull { it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return@runCatching null
        val ww = ProtobufWire.decode(wrapper.payload)
        val odField = ww.firstOrNull { it.fieldNumber == 3 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return@runCatching null
        val od = ProtobufWire.decode(odField.payload)
        val objects = od
            .filter { it.fieldNumber == 3 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { ProtobufWire.decode(it.payload) }
        val keys = od
            .filter { it.fieldNumber == 4 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { runCatching { it.payload.decodeToString() }.getOrDefault("") }
        val types = od
            .filter { it.fieldNumber == 5 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { runCatching { it.payload.decodeToString() }.getOrDefault("") }
        val uuids = od
            .filter { it.fieldNumber == 6 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            .map { it.payload }
        ObjectData(objects, keys, types, uuids)
    }.getOrNull()

    /** Top-level object kind we care about. */
    private enum class ObjectKind { LIST, DICT, STRING, CUSTOM_MAP, ORDERED_SET, UNKNOWN }

    private fun objectKind(obj: List<ProtobufWire.Field>): ObjectKind {
        val first = obj.firstOrNull() ?: return ObjectKind.UNKNOWN
        if (first.wireType != ProtobufWire.WIRE_LENGTH_DELIM) return ObjectKind.UNKNOWN
        return when (first.fieldNumber) {
            1 -> ObjectKind.LIST
            6 -> ObjectKind.DICT
            10 -> ObjectKind.STRING
            13 -> ObjectKind.CUSTOM_MAP
            16 -> ObjectKind.ORDERED_SET
            else -> ObjectKind.UNKNOWN
        }
    }

    private fun customMapTypeId(obj: List<ProtobufWire.Field>): Int? {
        val outer = obj.firstOrNull { it.fieldNumber == 13 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return null
        val inner = ProtobufWire.decode(outer.payload)
        val typeField = inner.firstOrNull { it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_VARINT }
            ?: return null
        return ProtobufWire.decodeVarint(typeField).toInt()
    }

    /**
     * Read CustomMap key->value entries. Values are returned as either an inline
     * "int/ref" (if f6 present) or a literal string (if f4 present). The caller
     * decides whether to interpret the int as an object_id ref by context (key
     * meaning).
     */
    private data class MapValue(val ref: Int? = null, val literal: String? = null)

    private fun customMapEntries(obj: List<ProtobufWire.Field>): Map<Int, MapValue> {
        val outer = obj.firstOrNull { it.fieldNumber == 13 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return emptyMap()
        val inner = ProtobufWire.decode(outer.payload)
        val out = LinkedHashMap<Int, MapValue>()
        for (f in inner) {
            if (f.fieldNumber != 3 || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val ent = ProtobufWire.decode(f.payload)
            val keyId = ent.firstOrNull { it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_VARINT }
                ?.let { ProtobufWire.decodeVarint(it).toInt() } ?: continue
            val valField = ent.firstOrNull { it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            if (valField == null) {
                out[keyId] = MapValue()
                continue
            }
            val vinner = ProtobufWire.decode(valField.payload)
            val refField = vinner.firstOrNull { it.fieldNumber == 6 && it.wireType == ProtobufWire.WIRE_VARINT }
            val litField = vinner.firstOrNull { it.fieldNumber == 4 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            out[keyId] = MapValue(
                ref = refField?.let { ProtobufWire.decodeVarint(it).toInt() },
                literal = litField?.let { runCatching { it.payload.decodeToString() }.getOrNull() },
            )
        }
        return out
    }

    /**
     * Walk an OrderedSet's entries and return target object refs in the order
     * they appear in the proto. Apple stores ordering metadata that we don't
     * fully reconstruct — proto serialization order is good enough for a
     * first-pass render.
     */
    private fun orderedSetRefs(obj: List<ProtobufWire.Field>): List<Int> {
        val outer = obj.firstOrNull { it.fieldNumber == 16 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return emptyList()
        val inner = ProtobufWire.decode(outer.payload)
        val nodesWrap = inner.firstOrNull { it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return emptyList()
        val nodes = ProtobufWire.decode(nodesWrap.payload)
        val out = ArrayList<Int>()
        for (n in nodes) {
            if (n.fieldNumber != 1 || n.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val nf = ProtobufWire.decode(n.payload)
            // Element { f1: target, f2: prev_or_self, f3: meta } — target is f1
            val target = nf.firstOrNull { it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            val targetRef = target?.let {
                ProtobufWire.decode(it.payload)
                    .firstOrNull { ff -> ff.fieldNumber == 6 && ff.wireType == ProtobufWire.WIRE_VARINT }
                    ?.let { v -> ProtobufWire.decodeVarint(v).toInt() }
            }
            if (targetRef != null) out.add(targetRef)
        }
        return out
    }

    /** Walk a Dict and return ordered (keyRef, valRef) pairs. */
    private fun dictEntries(obj: List<ProtobufWire.Field>): List<Pair<Int, Int>> {
        val outer = obj.firstOrNull { it.fieldNumber == 6 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return emptyList()
        val inner = ProtobufWire.decode(outer.payload)
        val out = ArrayList<Pair<Int, Int>>()
        for (e in inner) {
            if (e.fieldNumber != 1 || e.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val ef = ProtobufWire.decode(e.payload)
            val keyRef = ef.firstOrNull { it.fieldNumber == 1 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
                ?.let {
                    ProtobufWire.decode(it.payload)
                        .firstOrNull { ff -> ff.fieldNumber == 6 && ff.wireType == ProtobufWire.WIRE_VARINT }
                        ?.let { v -> ProtobufWire.decodeVarint(v).toInt() }
                } ?: continue
            val valRef = ef.firstOrNull { it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
                ?.let {
                    ProtobufWire.decode(it.payload)
                        .firstOrNull { ff -> ff.fieldNumber == 6 && ff.wireType == ProtobufWire.WIRE_VARINT }
                        ?.let { v -> ProtobufWire.decodeVarint(v).toInt() }
                } ?: continue
            out.add(keyRef to valRef)
        }
        return out
    }

    /** Read NSString.currentText (field 2 of the f10 wrapper). */
    private fun stringText(obj: List<ProtobufWire.Field>): String? {
        val outer = obj.firstOrNull { it.fieldNumber == 10 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return null
        val inner = ProtobufWire.decode(outer.payload)
        val text = inner.firstOrNull { it.fieldNumber == 2 && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?: return null
        return runCatching { text.payload.decodeToString() }.getOrNull()
    }

    private fun extractTable(od: ObjectData): TableGrid? {
        // 1. Locate the table root. iOS 17+ writes "com.apple.notes.ICTable";
        // older Notes wrote "com.apple.notes.CRTable". Both expose the same
        // self/crRows/crColumns/cellColumns shape, so either works as the root.
        val tableTypeNames = listOf(
            "com.apple.notes.ICTable",
            "com.apple.notes.ICTable2",
            "com.apple.notes.CRTable",
        )
        val tableTypeIdx = tableTypeNames.firstNotNullOfOrNull {
            od.types.indexOf(it).takeIf { idx -> idx >= 0 }
        } ?: return tableFallback(od)
        val icTableObj = od.objects.firstOrNull {
            objectKind(it) == ObjectKind.CUSTOM_MAP && customMapTypeId(it) == tableTypeIdx
        } ?: return tableFallback(od)

        // 2. Resolve key index for cellColumns. We use cellColumns as the
        // single source of truth for grid contents — its outer keys give the
        // column set, and the inner dicts give the row set per column. crRows
        // and crColumns OrderedSets carry the canonical visual order, but their
        // node-ref encoding doesn't match cellColumns' direct refs (they go
        // through a separate UUIDIndex layer we haven't fully decoded yet).
        // Iteration order in cellColumns matches insertion order, which
        // matches visual order for tables created left-to-right top-to-bottom.
        val keyCellColumns = od.keys.indexOf("cellColumns")
        if (keyCellColumns < 0) return tableFallback(od)
        val ent = customMapEntries(icTableObj)
        val cellColumnsRef = ent[keyCellColumns]?.ref ?: return tableFallback(od)
        val cellColumnsObj = od.objects.getOrNull(cellColumnsRef) ?: return tableFallback(od)

        // 3. Walk outer cellColumns dict: (col_ref, inner_dict_ref) pairs.
        val outer = dictEntries(cellColumnsObj)
        if (outer.isEmpty()) return tableFallback(od)

        // 4. Collect ordered column-keys + per-column row→string maps.
        val colOrder = outer.map { it.first }
        val perCol = LinkedHashMap<Int, List<Pair<Int, Int>>>()  // col_ref -> [(row_ref, str_ref)]
        for ((colRef, innerRef) in outer) {
            val innerObj = od.objects.getOrNull(innerRef) ?: continue
            perCol[colRef] = dictEntries(innerObj)
        }

        // 5. Row ordering: union of row_refs as they appear across columns,
        // preserving first-appearance order.
        val rowOrder = LinkedHashSet<Int>()
        for ((_, entries) in perCol) for ((rowRef, _) in entries) rowOrder.add(rowRef)
        val rowOrderList = rowOrder.toList()
        if (rowOrderList.isEmpty()) return tableFallback(od)

        // 6. Build grid.
        val grid = ArrayList<ArrayList<String>>()
        for (r in rowOrderList.indices) grid.add(ArrayList<String>().apply { repeat(colOrder.size) { add("") } })
        for ((cIdx, colRef) in colOrder.withIndex()) {
            val rows = perCol[colRef] ?: continue
            for ((rowRef, strRef) in rows) {
                val rIdx = rowOrderList.indexOf(rowRef)
                if (rIdx < 0) continue
                val strObj = od.objects.getOrNull(strRef) ?: continue
                val text = stringText(strObj) ?: continue
                grid[rIdx][cIdx] = text
            }
        }
        return TableGrid(rows = rowOrderList.size, cols = colOrder.size, cells = grid)
    }

    /**
     * Fallback path when we can't follow the graph fully — emit every NSString
     * value we find as a single-column table so the user at least sees the cell
     * text content.
     */
    private fun tableFallback(od: ObjectData): TableGrid? {
        val texts = od.objects
            .filter { objectKind(it) == ObjectKind.STRING }
            .mapNotNull { stringText(it) }
            .filter { it.isNotEmpty() && !it.startsWith("CRTable") }
        if (texts.isEmpty()) return null
        return TableGrid(rows = texts.size, cols = 1, cells = texts.map { listOf(it) })
    }
}
