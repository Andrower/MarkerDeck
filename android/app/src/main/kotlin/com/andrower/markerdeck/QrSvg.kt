package com.andrower.markerdeck

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

fun buildHostQrSvg(value: String, size: Int = 256): String {
    require(value.isNotBlank()) { "QR value must not be blank." }
    require(size in 64..1024) { "QR size must be between 64 and 1024." }
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 2)
    )
    val svg = StringBuilder(size * 12)
        .append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $size $size\" role=\"img\" aria-label=\"MarkerDeck 地址二维码\">")
        .append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
    for (y in 0 until size) {
        for (x in 0 until size) {
            if (matrix.get(x, y)) {
                svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" width=\"1\" height=\"1\" fill=\"black\"/>")
            }
        }
    }
    return svg.append("</svg>").toString()
}
