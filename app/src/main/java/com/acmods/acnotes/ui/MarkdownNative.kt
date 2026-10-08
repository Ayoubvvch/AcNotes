package com.acmods.acnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acmods.acnotes.ui.theme.*

fun isArabicOrRtl(text: String): Boolean {
    for (char in text) {
        val code = char.code
        if (code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF) {
            return true
        }
        if (char in 'a'..'z' || char in 'A'..'Z') {
            return false
        }
    }
    return false
}

sealed class MdBlock {
    data class Header(val level: Int, val text: String) : MdBlock()
    data class Task(val checked: Boolean, val text: String, val lineIndex: Int) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    object Divider : MdBlock()
}

fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MdBlock>()
    var inCodeBlock = false
    val codeAccumulator = StringBuilder()

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MdBlock.CodeBlock(codeAccumulator.toString().trimEnd()))
                codeAccumulator.clear()
                inCodeBlock = false
            } else {
                inCodeBlock = true
            }
            continue
        }

        if (inCodeBlock) {
            codeAccumulator.appendLine(line)
            continue
        }

        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MdBlock.Divider)
            continue
        }

        if (trimmed.startsWith("# ")) {
            blocks.add(MdBlock.Header(1, trimmed.removePrefix("# ").trim()))
        } else if (trimmed.startsWith("## ")) {
            blocks.add(MdBlock.Header(2, trimmed.removePrefix("## ").trim()))
        } else if (trimmed.startsWith("### ")) {
            blocks.add(MdBlock.Header(3, trimmed.removePrefix("### ").trim()))
        } else if (trimmed.matches(Regex("^[-*+]\\s*\\[[ xX]\\]\\s*.*"))) {
            val isChecked = trimmed.contains("[x]") || trimmed.contains("[X]")
            val content = trimmed.replace(Regex("^[-*+]\\s*\\[[ xX]\\]\\s*"), "")
            blocks.add(MdBlock.Task(isChecked, content, index))
        } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            blocks.add(MdBlock.Bullet(trimmed.substring(2).trim()))
        } else if (trimmed.startsWith("> ")) {
            blocks.add(MdBlock.Quote(trimmed.removePrefix("> ").trim()))
        } else if (trimmed.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(line))
        }
    }

    if (inCodeBlock && codeAccumulator.isNotEmpty()) {
        blocks.add(MdBlock.CodeBlock(codeAccumulator.toString().trimEnd()))
    }

    return blocks
}

@Composable
fun MarkdownNativeView(
    content: String,
    modifier: Modifier = Modifier,
    onToggleTask: ((Int, Boolean) -> Unit)? = null
) {
    val blocks = parseMarkdownBlocks(content)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Header -> {
                    val isRtl = isArabicOrRtl(block.text)
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Text(
                            text = formatInlineMarkdown(block.text),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = when (block.level) {
                                1 -> 24.sp
                                2 -> 20.sp
                                else -> 17.sp
                            },
                            lineHeight = when (block.level) {
                                1 -> 30.sp
                                2 -> 26.sp
                                else -> 23.sp
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 4.dp)
                        )
                    }
                }
                is MdBlock.Task -> {
                    val isRtl = isArabicOrRtl(block.text)
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = block.checked,
                                onCheckedChange = { isChecked ->
                                    onToggleTask?.invoke(block.lineIndex, isChecked)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = EmeraldPrimary,
                                    uncheckedColor = TextSecondary,
                                    checkmarkColor = BgDark
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = formatInlineMarkdown(block.text),
                                color = if (block.checked) TextMuted else TextPrimary,
                                fontSize = 15.sp,
                                textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is MdBlock.Bullet -> {
                    val isRtl = isArabicOrRtl(block.text)
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .size(6.dp)
                                    .background(EmeraldPrimary, RoundedCornerShape(3.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = formatInlineMarkdown(block.text),
                                color = TextPrimary,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                is MdBlock.Quote -> {
                    val isRtl = isArabicOrRtl(block.text)
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x1010B981))
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(EmeraldPrimary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = formatInlineMarkdown(block.text),
                                color = TextSecondary,
                                fontStyle = FontStyle.Italic,
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
                is MdBlock.CodeBlock -> {
                    Surface(
                        color = Color(0xFF101014),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SurfaceBorder, RoundedCornerShape(10.dp))
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = block.code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = EmeraldLight,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
                is MdBlock.Divider -> {
                    Divider(
                        color = SurfaceBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                is MdBlock.Paragraph -> {
                    val isRtl = isArabicOrRtl(block.text)
                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Text(
                            text = formatInlineMarkdown(block.text),
                            color = TextPrimary,
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

fun formatInlineMarkdown(raw: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < raw.size) {
            if (i + 1 < raw.size && raw[i] == '*' && raw[i + 1] == '*') {
                val end = raw.indexOf("**", i + 2)
                if (end != -1) {
                    val boldText = raw.substring(i + 2, end)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary))
                    append(boldText)
                    pop()
                    i = end + 2
                    continue
                }
            } else if (raw[i] == '*') {
                val end = raw.indexOf("*", i + 1)
                if (end != -1) {
                    val italicText = raw.substring(i + 1, end)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = TextSecondary))
                    append(italicText)
                    pop()
                    i = end + 1
                    continue
                }
            } else if (raw[i] == '`') {
                val end = raw.indexOf("`", i + 1)
                if (end != -1) {
                    val codeText = raw.substring(i + 1, end)
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = EmeraldLight, background = SurfaceDark))
                    append(" $codeText ")
                    pop()
                    i = end + 1
                    continue
                }
            }
            append(raw[i])
            i++
        }
    }
}

val String.size: Int get() = this.length
