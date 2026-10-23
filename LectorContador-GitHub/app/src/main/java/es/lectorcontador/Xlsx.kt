package es.lectorcontador

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Escritor mínimo de archivos .xlsx (Office Open XML) sin dependencias: hojas con estilos, celdas
 * combinadas, fórmulas con su valor en caché y un gráfico de barras. Sin Android, para poder probarlo.
 */

/** Estilos disponibles (índices de cellXfs en styles.xml). Imitan el Excel de referencia. */
object XS {
    const val DEF = 0
    const val TITLE = 1
    const val SUBTITLE = 2
    const val SEC_BLUE = 3
    const val SEC_NAVY = 4
    const val SEC_ORANGE = 5
    const val HEAD = 6
    const val HEAD_BLUE = 7
    const val CELL_L = 8
    const val CELL_C = 9
    const val CELL_NUM = 10
    const val CELL_COS = 11
    const val SUM_LABEL = 12
    const val SUM_NUM = 13
    const val SUM_TXT = 14
    const val SUM_UNIT = 15
    const val SUM_COS = 16
    const val TOT_C = 17
    const val TOT_L = 18
    const val TOT_NUM = 19
    const val H2 = 20
    const val CELL_R = 21
    const val COUNT = 22
}

fun xmlEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (ch in s) {
        when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> sb.append(' ')
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** Número para XML sin notación científica (Excel la acepta, pero así es más legible). */
fun xmlNum(v: Double): String =
    if (v.isNaN() || v.isInfinite()) "0" else BigDecimal(v.toString()).toPlainString()

fun colLetter(col: Int): String {
    var c = col
    val sb = StringBuilder()
    while (c > 0) {
        val r = (c - 1) % 26
        sb.insert(0, ('A'.code + r).toChar())
        c = (c - 1) / 26
    }
    return sb.toString()
}

class XCell(val kind: Char, val text: String?, val num: Double?, val style: Int)

class XSheet(val name: String) {
    private val cells = java.util.TreeMap<Int, java.util.TreeMap<Int, XCell>>()
    private val merges = ArrayList<String>()
    val widths = java.util.TreeMap<Int, Double>()

    private fun put(row: Int, col: Int, cell: XCell) {
        cells.getOrPut(row) { java.util.TreeMap() }[col] = cell
    }

    fun str(row: Int, col: Int, text: String, style: Int) = put(row, col, XCell('s', text, null, style))
    fun num(row: Int, col: Int, v: Double, style: Int) = put(row, col, XCell('n', null, v, style))
    fun blank(row: Int, col: Int, style: Int) = put(row, col, XCell('b', null, null, style))

    /** Fórmula (sin el '=') con el valor ya calculado, para visores que no recalculan. */
    fun formula(row: Int, col: Int, f: String, style: Int, cached: Double) =
        put(row, col, XCell('f', f, cached, style))

    fun merge(r1: Int, c1: Int, r2: Int, c2: Int) {
        merges.add("${colLetter(c1)}$r1:${colLetter(c2)}$r2")
    }

    fun toXml(hasDrawing: Boolean): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        if (cells.isNotEmpty()) {
            val minR = cells.firstKey()
            val maxR = cells.lastKey()
            var minC = Int.MAX_VALUE
            var maxC = 0
            for (row in cells.values) {
                minC = minOf(minC, row.firstKey())
                maxC = maxOf(maxC, row.lastKey())
            }
            sb.append("<dimension ref=\"${colLetter(minC)}$minR:${colLetter(maxC)}$maxR\"/>")
        }
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        if (widths.isNotEmpty()) {
            sb.append("<cols>")
            for ((c, w) in widths) sb.append("<col min=\"$c\" max=\"$c\" width=\"$w\" customWidth=\"1\"/>")
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        for ((r, row) in cells) {
            sb.append("<row r=\"$r\">")
            for ((c, cell) in row) {
                val ref = "${colLetter(c)}$r"
                when (cell.kind) {
                    's' -> sb.append(
                        "<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
                            "${xmlEscape(cell.text ?: "")}</t></is></c>"
                    )
                    'n' -> sb.append("<c r=\"$ref\" s=\"${cell.style}\"><v>${xmlNum(cell.num ?: 0.0)}</v></c>")
                    'f' -> sb.append(
                        "<c r=\"$ref\" s=\"${cell.style}\"><f>${xmlEscape(cell.text ?: "")}</f>" +
                            "<v>${xmlNum(cell.num ?: 0.0)}</v></c>"
                    )
                    else -> sb.append("<c r=\"$ref\" s=\"${cell.style}\"/>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${merges.size}\">")
            for (m in merges) sb.append("<mergeCell ref=\"$m\"/>")
            sb.append("</mergeCells>")
        }
        sb.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        if (hasDrawing) sb.append("<drawing r:id=\"rId1\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }
}

object XlsxWriter {
    private const val HDR = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun font(bold: Boolean, size: Int, rgb: String) =
        "<font>${if (bold) "<b/>" else ""}<sz val=\"$size\"/><color rgb=\"FF$rgb\"/><name val=\"Segoe UI\"/></font>"

    private fun solid(rgb: String) =
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF$rgb\"/><bgColor indexed=\"64\"/></patternFill></fill>"

    private fun side(tag: String, style: String, rgb: String) = "<$tag style=\"$style\"><color rgb=\"FF$rgb\"/></$tag>"

    private fun border(l: String, r: String, t: String, b: String) = "<border>$l$r$t$b<diagonal/></border>"

    private fun xf(font: Int = 0, fill: Int = 0, border: Int = 0, num: Int = 0, h: String? = null, v: String? = "center"): String {
        val al = if (h != null || v != null) {
            "<alignment" + (if (h != null) " horizontal=\"$h\"" else "") +
                (if (v != null) " vertical=\"$v\"" else "") + "/>"
        } else ""
        return "<xf numFmtId=\"$num\" fontId=\"$font\" fillId=\"$fill\" borderId=\"$border\" xfId=\"0\" " +
            "applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\">$al</xf>"
    }

    fun stylesXml(): String {
        val fonts = listOf(
            font(false, 10, "000000"), font(true, 16, "1F497D"), font(false, 10, "595959"),
            font(true, 11, "FFFFFF"), font(true, 10, "FFFFFF"), font(true, 10, "000000"), font(true, 11, "1F497D"),
        )
        val fills = listOf(
            "<fill><patternFill patternType=\"none\"/></fill>",
            "<fill><patternFill patternType=\"gray125\"/></fill>",
            solid("1F497D"), solid("2E6B9E"), solid("C65911"), solid("F8F9FA"), solid("EDF2F8"),
        )
        fun thin(tag: String) = side(tag, "thin", "D9D9D9")
        val borders = listOf(
            "<border><left/><right/><top/><bottom/><diagonal/></border>",
            border(thin("left"), thin("right"), thin("top"), thin("bottom")),
            border(thin("left"), thin("right"), side("top", "medium", "1F497D"), side("bottom", "medium", "1F497D")),
            border(thin("left"), thin("right"), thin("top"), side("bottom", "double", "1F497D")),
        )
        val xfs = arrayOfNulls<String>(XS.COUNT)
        xfs[XS.DEF] = xf(v = null)
        xfs[XS.TITLE] = xf(font = 1, v = null)
        xfs[XS.SUBTITLE] = xf(font = 2, v = null)
        xfs[XS.SEC_BLUE] = xf(font = 3, fill = 3, h = "center")
        xfs[XS.SEC_NAVY] = xf(font = 3, fill = 2, h = "center")
        xfs[XS.SEC_ORANGE] = xf(font = 3, fill = 4, h = "center")
        xfs[XS.HEAD] = xf(font = 4, fill = 2, border = 2, h = "center")
        xfs[XS.HEAD_BLUE] = xf(font = 4, fill = 3, border = 2, h = "center")
        xfs[XS.CELL_L] = xf(border = 1, h = "left")
        xfs[XS.CELL_C] = xf(border = 1, h = "center")
        xfs[XS.CELL_NUM] = xf(border = 1, num = 164, h = "right")
        xfs[XS.CELL_COS] = xf(border = 1, num = 165, h = "right")
        xfs[XS.SUM_LABEL] = xf(font = 5, fill = 5, border = 1, h = "left")
        xfs[XS.SUM_NUM] = xf(fill = 5, border = 1, num = 164, h = "right")
        xfs[XS.SUM_TXT] = xf(fill = 5, border = 1, h = "right")
        xfs[XS.SUM_UNIT] = xf(fill = 5, border = 1, h = "left")
        xfs[XS.SUM_COS] = xf(fill = 5, border = 1, num = 165, h = "right")
        xfs[XS.TOT_C] = xf(font = 5, fill = 6, border = 3, h = "center")
        xfs[XS.TOT_L] = xf(font = 5, fill = 6, border = 3, h = "left")
        xfs[XS.TOT_NUM] = xf(font = 5, fill = 6, border = 3, num = 164, h = "right")
        xfs[XS.H2] = xf(font = 6, v = null)
        xfs[XS.CELL_R] = xf(border = 1, h = "right")

        val sb = StringBuilder(HDR)
        sb.append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.0\"/>")
        sb.append("<numFmt numFmtId=\"165\" formatCode=\"0.000\"/></numFmts>")
        sb.append("<fonts count=\"${fonts.size}\">").append(fonts.joinToString("")).append("</fonts>")
        sb.append("<fills count=\"${fills.size}\">").append(fills.joinToString("")).append("</fills>")
        sb.append("<borders count=\"${borders.size}\">").append(borders.joinToString("")).append("</borders>")
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        sb.append("<cellXfs count=\"${xfs.size}\">").append(xfs.joinToString("")).append("</cellXfs>")
        sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        sb.append("</styleSheet>")
        return sb.toString()
    }

    private fun rich(text: String, size: Int, rot: Int? = null): String {
        val r = if (rot != null) " rot=\"$rot\"" else ""
        return "<c:title><c:tx><c:rich><a:bodyPr$r/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"$size\" b=\"1\"/></a:pPr>" +
            "<a:r><a:rPr lang=\"ca-ES\" sz=\"$size\" b=\"1\"/><a:t>${xmlEscape(text)}</a:t></a:r></a:p></c:rich></c:tx>" +
            "<c:overlay val=\"0\"/></c:title>"
    }

    /** Gráfico de barras de la hoja [sheet]: categorías en B[first..last], valores en D[first..last]. */
    fun chartXml(
        title: String, seriesName: String, sheet: String, first: Int, last: Int,
        cats: List<String>, vals: List<Double>,
    ): String {
        val q = "'${xmlEscape(sheet)}'"
        val catPts = cats.mapIndexed { i, t -> "<c:pt idx=\"$i\"><c:v>${xmlEscape(t)}</c:v></c:pt>" }.joinToString("")
        val valPts = vals.mapIndexed { i, v -> "<c:pt idx=\"$i\"><c:v>${xmlNum(v)}</c:v></c:pt>" }.joinToString("")
        val sb = StringBuilder(HDR)
        sb.append("<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" ")
        sb.append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
        sb.append("xmlns:r=\"$OD\"><c:roundedCorners val=\"0\"/><c:chart>")
        sb.append(rich(title, 1400))
        sb.append("<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/>")
        sb.append("<c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>")
        sb.append("<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/>")
        sb.append("<c:tx><c:strRef><c:f>$q!\$D\$5</c:f><c:strCache><c:ptCount val=\"1\"/>")
        sb.append("<c:pt idx=\"0\"><c:v>${xmlEscape(seriesName)}</c:v></c:pt></c:strCache></c:strRef></c:tx>")
        sb.append("<c:spPr><a:solidFill><a:srgbClr val=\"4F81BD\"/></a:solidFill></c:spPr><c:invertIfNegative val=\"0\"/>")
        sb.append("<c:cat><c:strRef><c:f>$q!\$B\$$first:\$B\$$last</c:f><c:strCache><c:ptCount val=\"${cats.size}\"/>")
        sb.append(catPts).append("</c:strCache></c:strRef></c:cat>")
        sb.append("<c:val><c:numRef><c:f>$q!\$D\$$first:\$D\$$last</c:f><c:numCache><c:formatCode>#,##0.0</c:formatCode>")
        sb.append("<c:ptCount val=\"${vals.size}\"/>").append(valPts).append("</c:numCache></c:numRef></c:val></c:ser>")
        sb.append("<c:gapWidth val=\"150\"/><c:axId val=\"3955679\"/><c:axId val=\"67498296\"/></c:barChart>")
        sb.append("<c:catAx><c:axId val=\"3955679\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling>")
        sb.append("<c:delete val=\"0\"/><c:axPos val=\"b\"/>").append(rich("Tancament", 1000))
        sb.append("<c:numFmt formatCode=\"General\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/>")
        sb.append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"67498296\"/>")
        sb.append("<c:crosses val=\"autoZero\"/><c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/>")
        sb.append("<c:noMultiLvlLbl val=\"0\"/></c:catAx>")
        sb.append("<c:valAx><c:axId val=\"67498296\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling>")
        sb.append("<c:delete val=\"0\"/><c:axPos val=\"l\"/><c:majorGridlines><c:spPr><a:ln w=\"9360\"><a:solidFill>")
        sb.append("<a:srgbClr val=\"D9D9D9\"/></a:solidFill></a:ln></c:spPr></c:majorGridlines>")
        sb.append(rich("kWh", 1000, -5400000))
        sb.append("<c:numFmt formatCode=\"#,##0.0\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/>")
        sb.append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"3955679\"/>")
        sb.append("<c:crosses val=\"autoZero\"/><c:crossBetween val=\"between\"/></c:valAx>")
        sb.append("</c:plotArea><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart>")
        sb.append("<c:spPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:ln w=\"9360\"><a:solidFill>")
        sb.append("<a:srgbClr val=\"D9D9D9\"/></a:solidFill></a:ln></c:spPr></c:chartSpace>")
        return sb.toString()
    }

    private val DRAWING = HDR +
        "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">" +
        "<xdr:twoCellAnchor editAs=\"oneCell\"><xdr:from><xdr:col>7</xdr:col><xdr:colOff>0</xdr:colOff>" +
        "<xdr:row>4</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>" +
        "<xdr:to><xdr:col>17</xdr:col><xdr:colOff>358200</xdr:colOff><xdr:row>26</xdr:row><xdr:rowOff>128520</xdr:rowOff></xdr:to>" +
        "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"2\" name=\"Gràfic 1\"/><xdr:cNvGraphicFramePr/>" +
        "</xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic>" +
        "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">" +
        "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" " +
        "xmlns:r=\"$OD\" r:id=\"rId1\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/>" +
        "</xdr:twoCellAnchor></xdr:wsDr>"

    private fun rels(vararg items: Triple<String, String, String>): String {
        val sb = StringBuilder(HDR)
        sb.append("<Relationships xmlns=\"$REL_NS\">")
        for ((id, type, target) in items) {
            sb.append("<Relationship Id=\"$id\" Type=\"$OD/$type\" Target=\"$target\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    /** Nombre de hoja válido en Excel: sin \ / ? * [ ] : y como mucho 31 caracteres. */
    fun sheetName(name: String): String = name.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31)

    /**
     * Genera el libro. Si [chartXml] no es null, se coloca en la primera hoja (con la
     * disposición de la hoja de resumen: gráfico a la derecha de la tabla).
     */
    fun write(sheets: List<XSheet>, chartXml: String?): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun entry(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            val ct = StringBuilder(HDR)
            ct.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            ct.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            ct.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            for (i in sheets.indices) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            if (chartXml != null) {
                ct.append("<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>")
                ct.append("<Override PartName=\"/xl/charts/chart1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>")
            }
            ct.append("</Types>")
            entry("[Content_Types].xml", ct.toString())
            entry("_rels/.rels", rels(Triple("rId1", "officeDocument", "xl/workbook.xml")))

            val wb = StringBuilder(HDR)
            wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$OD\">")
            wb.append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>")
            sheets.forEachIndexed { i, s ->
                wb.append("<sheet name=\"${xmlEscape(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
            }
            wb.append("</sheets></workbook>")
            entry("xl/workbook.xml", wb.toString())

            val wbRels = ArrayList<Triple<String, String, String>>()
            sheets.indices.forEach { i -> wbRels.add(Triple("rId${i + 1}", "worksheet", "worksheets/sheet${i + 1}.xml")) }
            wbRels.add(Triple("rId${sheets.size + 1}", "styles", "styles.xml"))
            entry("xl/_rels/workbook.xml.rels", rels(*wbRels.toTypedArray()))
            entry("xl/styles.xml", stylesXml())

            sheets.forEachIndexed { i, s -> entry("xl/worksheets/sheet${i + 1}.xml", s.toXml(i == 0 && chartXml != null)) }
            if (chartXml != null) {
                entry("xl/worksheets/_rels/sheet1.xml.rels", rels(Triple("rId1", "drawing", "../drawings/drawing1.xml")))
                entry("xl/drawings/drawing1.xml", DRAWING)
                entry("xl/drawings/_rels/drawing1.xml.rels", rels(Triple("rId1", "chart", "../charts/chart1.xml")))
                entry("xl/charts/chart1.xml", chartXml)
            }
        }
        return bos.toByteArray()
    }
}
