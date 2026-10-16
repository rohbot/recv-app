package cc.roh.soundreceiver;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Incremental UTF-8 + LF/CRLF framing, bounded even for devices without newlines. */
public final class SerialLines {
    public static final int MAX_LENGTH = 8192;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final Consumer<String> onLine;
    private final StringBuilder pending = new StringBuilder();
    private byte[] remainder = new byte[0];
    private boolean dropping;
    private boolean finished;

    public SerialLines(Consumer<String> onLine) { this.onLine = onLine; }

    public void push(byte[] bytes, int length) {
        if (finished) throw new IllegalStateException("Stream already finished");
        ByteBuffer input = ByteBuffer.allocate(remainder.length + length);
        input.put(remainder).put(bytes, 0, length).flip();
        decode(input, false);
        remainder = new byte[input.remaining()];
        input.get(remainder);
    }

    private void decode(ByteBuffer input, boolean end) {
        CharBuffer output = CharBuffer.allocate(2048);
        CoderResult result;
        do {
            result = decoder.decode(input, output, end);
            consume(output);
        } while (result.isOverflow());
    }

    private void consume(CharBuffer output) {
        output.flip();
        while (output.hasRemaining()) {
            char c = output.get();
            if (c == '\n') {
                if (!dropping) emit();
                pending.setLength(0);
                dropping = false;
            } else if (!dropping) {
                if (pending.length() == MAX_LENGTH) {
                    onLine.accept(pending.substring(0, MAX_LENGTH - 17) + " [line truncated]");
                    pending.setLength(0);
                    dropping = true;
                } else pending.append(c);
            }
        }
        output.clear();
    }

    private void emit() {
        int length = pending.length();
        if (length > 0 && pending.charAt(length - 1) == '\r') length--;
        if (length > 0) onLine.accept(pending.substring(0, length));
    }

    public void finish() {
        if (finished) return;
        finished = true;
        decode(ByteBuffer.wrap(remainder), true);
        CharBuffer output = CharBuffer.allocate(16);
        decoder.flush(output);
        consume(output);
        if (!dropping) emit();
        pending.setLength(0);
        remainder = new byte[0];
    }
}
