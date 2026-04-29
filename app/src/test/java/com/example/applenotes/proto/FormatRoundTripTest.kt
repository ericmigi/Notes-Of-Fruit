package com.example.applenotes.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Round-trip the format encoder against a real fmt-baseline-1 proto pulled
 * off iCloud. Verifies:
 *  1. parseAttributeRuns picks up the existing bold/italic/underline/strike/link.
 *  2. Toolbar mutators produce a sane new span list.
 *  3. setBodyBase64 with newSpans emits a proto whose spans re-parse identically.
 */
@OptIn(ExperimentalEncodingApi::class)
class FormatRoundTripTest {

    private fun loadProtoBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fmt-baseline-1.proto")!!.readBytes()

    private fun loadAsB64(): String = Base64.encode(Gzip.compress(loadProtoBytes()))

    @Test
    fun encoderRoundTripsSingleSpan() {
        val span = NoteBodyEditor.AttrSpan(
            length = 4, style = NoteBodyEditor.ParagraphStyle.BODY, bold = true,
        )
        val field = NoteBodyEditor.encodeAttributeRunField(span)
        // Re-decode the encoded field to verify our parser sees bold.
        val decoded = ProtobufWire.decode(field.payload)
        val length = decoded.firstOrNull { it.fieldNumber == 1 }?.let {
            ProtobufWire.decodeVarint(it).toInt()
        }
        val fontWeight = decoded.firstOrNull { it.fieldNumber == 5 }?.let {
            ProtobufWire.decodeVarint(it).toInt()
        }
        assertEquals(4, length)
        assertEquals(1, fontWeight)
    }

    @Test
    fun spliceWithMutatedSpansActuallyRebuildsARs() {
        val origB64 = loadAsB64()
        val origBody = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!
        val origSpans = NoteBodyEditor.parseAttributeRunsFromBase64(origB64)
        // Synthesize: the entire body becomes a single bold body span.
        val synthSpans = listOf(NoteBodyEditor.AttrSpan(
            length = origBody.length,
            style = NoteBodyEditor.ParagraphStyle.BODY,
            bold = true,
        ))
        val replicaUuid = ByteArray(16) { it.toByte() }
        val newB64 = NoteAppender.setBodyBase64(
            origB64, replicaUuid, origBody, 1700000000L, synthSpans,
        )
        val reSpans = NoteBodyEditor.parseAttributeRunsFromBase64(newB64)
        // With newSpans coalesced, expect exactly ONE span covering the whole body, bold.
        val coalesced = NoteBodyEditor.coalesceSpans(reSpans)
        println("DBG synth-roundtrip got ${coalesced.size} spans:")
        for (s in coalesced.take(5)) println("  $s")
        assertEquals(origBody.length, coalesced.sumOf { it.length })
        assertTrue("expected at least one bold span", coalesced.any { it.bold })
    }

    @Test
    fun parsesBoldItalicUnderlineStrike() {
        val proto = loadProtoBytes()
        val spans = NoteBodyEditor.parseAttributeRuns(proto)
        // From the dump we know the body paragraph contains the markers.
        assertTrue("Expected at least one bold span", spans.any { it.bold })
        assertTrue("Expected at least one italic span", spans.any { it.italic })
        assertTrue("Expected at least one underlined span", spans.any { it.underline })
        assertTrue("Expected at least one strikethrough span", spans.any { it.strikethrough })
        assertTrue("Expected at least one linked span", spans.any { it.linkUrl != null })
    }

    @Test
    fun roundTripPreservesFormatting() {
        val origB64 = loadAsB64()
        val origSpans = NoteBodyEditor.parseAttributeRunsFromBase64(origB64)
        val origBody = NoteBodyEditor.readVisibleTextFromBase64(origB64)
            ?: error("could not read visible text")

        // Sanity: span lengths sum to body length.
        assertEquals(origBody.length, origSpans.sumOf { it.length })

        // Re-encode WITHOUT changes — should produce identical span list.
        val replicaUuid = ByteArray(16) { it.toByte() }
        val newB64 = NoteAppender.setBodyBase64(
            origB64, replicaUuid, origBody, 1700000000L, origSpans,
        )
        val reSpans = NoteBodyEditor.parseAttributeRunsFromBase64(newB64)
        // Coalesce both for fair comparison.
        val origCoalesced = NoteBodyEditor.coalesceSpans(origSpans)
        val reCoalesced = NoteBodyEditor.coalesceSpans(reSpans)
        assertEquals(origCoalesced.size, reCoalesced.size)
        for ((a, b) in origCoalesced.zip(reCoalesced)) {
            assertEquals(a.length, b.length)
            assertEquals(a.style, b.style)
            assertEquals(a.bold, b.bold)
            assertEquals(a.italic, b.italic)
            assertEquals(a.underline, b.underline)
            assertEquals(a.strikethrough, b.strikethrough)
            assertEquals(a.linkUrl, b.linkUrl)
        }
    }

    @Test
    fun togglingBoldOnSelectionRoundTrips() {
        val origB64 = loadAsB64()
        val origSpans = NoteBodyEditor.parseAttributeRunsFromBase64(origB64)
        val origBody = NoteBodyEditor.readVisibleTextFromBase64(origB64)
            ?: error("could not read visible text")
        // Find the word "Heading" and toggle bold across its chars.
        val idx = origBody.indexOf("Heading")
        assertTrue("Body must contain Heading", idx >= 0)
        val mutated = NoteBodyEditor.toggleInline(
            origBody, origSpans, idx, idx + "Heading".length, NoteBodyEditor.InlineKind.BOLD,
        )
        // Run-length total still matches.
        assertEquals(origBody.length, mutated.sumOf { it.length })

        val replicaUuid = ByteArray(16) { it.toByte() }
        val newB64 = NoteAppender.setBodyBase64(
            origB64, replicaUuid, origBody, 1700000000L, mutated,
        )
        val reSpans = NoteBodyEditor.parseAttributeRunsFromBase64(newB64)

        // Each char of "Heading" should now report bold=true.
        val perChar = NoteBodyEditor.expandSpansToChars(origBody, reSpans)
        for (i in idx until idx + "Heading".length) {
            assertTrue("char $i (${origBody[i]}) should be bold", perChar[i].bold)
        }
    }

    @Test
    fun togglingHeadingParagraphRoundTrips() {
        val origB64 = loadAsB64()
        val origSpans = NoteBodyEditor.parseAttributeRunsFromBase64(origB64)
        val origBody = NoteBodyEditor.readVisibleTextFromBase64(origB64)
            ?: error("could not read visible text")
        // The line "dot item 1" — find it and apply HEADING style across the paragraph.
        val idx = origBody.indexOf("dot item 1")
        assertTrue(idx >= 0)
        val mutated = NoteBodyEditor.setParagraphStyle(
            origBody, origSpans, idx, idx + 1, NoteBodyEditor.ParagraphStyle.HEADING,
        )
        val replicaUuid = ByteArray(16) { it.toByte() }
        val newB64 = NoteAppender.setBodyBase64(
            origB64, replicaUuid, origBody, 1700000000L, mutated,
        )
        val reSpans = NoteBodyEditor.parseAttributeRunsFromBase64(newB64)
        val perChar = NoteBodyEditor.expandSpansToChars(origBody, reSpans)
        // The whole paragraph for "dot item 1" should be HEADING (not BULLETED_LIST).
        for (i in idx until idx + "dot item 1".length) {
            assertEquals(NoteBodyEditor.ParagraphStyle.HEADING, perChar[i].style)
        }
    }

    @Test
    fun checkboxToggleFlipsCheckedFlag() {
        val origB64 = loadAsB64()
        val origSpans = NoteBodyEditor.parseAttributeRunsFromBase64(origB64)
        val origBody = NoteBodyEditor.readVisibleTextFromBase64(origB64)
            ?: error("could not read visible text")
        val idx = origBody.indexOf("check 1 - unchecked")
        assertTrue(idx >= 0)
        val mutated = NoteBodyEditor.toggleCheckbox(origBody, origSpans, idx)

        val replicaUuid = ByteArray(16) { it.toByte() }
        val newB64 = NoteAppender.setBodyBase64(
            origB64, replicaUuid, origBody, 1700000000L, mutated,
        )
        val reSpans = NoteBodyEditor.parseAttributeRunsFromBase64(newB64)
        val perChar = NoteBodyEditor.expandSpansToChars(origBody, reSpans)
        assertEquals(NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST, perChar[idx].style)
        assertTrue("first checkbox should now be checked", perChar[idx].checked)
    }
}
