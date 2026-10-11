package com.smartlib.seed;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Writes a small, valid one-page PDF (Helvetica text) — used for the seeded library guides. */
final class MiniPdf {
    private MiniPdf() {
    }

    static byte[] create(String title, List<String> lines) {
        StringBuilder content = new StringBuilder();
        content.append("BT /F1 20 Tf 56 780 Td (").append(escape(title)).append(") Tj ET\n");
        content.append("BT /F1 11 Tf 56 748 Td 16 TL\n");
        for (String line : lines) {
            content.append('(').append(escape(line)).append(") Tj T*\n");
        }
        content.append("ET\n");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n");
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                null,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"};
        for (int i = 0; i < objects.length; i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n");
            if (objects[i] == null) {
                write(out, "<< /Length " + stream.length + " >>\nstream\n");
                out.writeBytes(stream);
                write(out, "\nendstream\n");
            } else {
                write(out, objects[i] + "\n");
            }
            write(out, "endobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            write(out, String.format("%010d 00000 n \n", off));
        }
        write(out, "trailer\n<< /Size " + (objects.length + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
