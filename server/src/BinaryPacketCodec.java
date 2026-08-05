import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class BinaryPacketCodec
{
    public static final int PACKET_MAGIC = 0xD731;
    public static final int BATCH_MAGIC = 0xD732;
    private static final int MAX_FIELDS = 255;
    private static final int MAX_FIELD_BYTES = 65535;
    private static final int MAX_PACKET_BYTES = 1 << 20;
    private static final int TYPE_STRING = 0;
    private static final int TYPE_INT32 = 1;
    private static final int TYPE_INT64 = 2;
    private static final int TYPE_FLOAT32 = 3;
    private static final int TYPE_STRING16 = 4;

    private BinaryPacketCodec()
    {
    }

    public static byte[] encodeText(String packetText)
    {
        List<String> parts = splitEscaped(packetText);
        if (parts.isEmpty())
        {
            throw new IllegalArgumentException("empty packet");
        }

        PacketRegistry.Spec spec = PacketRegistry.byOpcode(parts.get(0));
        if (spec == null)
        {
            throw new IllegalArgumentException("unregistered opcode " + parts.get(0));
        }

        int fieldCount = parts.size() - 1;
        if (fieldCount > MAX_FIELDS)
        {
            throw new IllegalArgumentException("too many fields");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32, packetText.length()));
        writeShort(output, PACKET_MAGIC);
        output.write(spec.id);
        output.write(fieldCount);

        for (int i = 1; i < parts.size(); i++)
        {
            writeField(output, parts.get(i));
        }

        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_PACKET_BYTES)
        {
            throw new IllegalArgumentException("packet too large");
        }
        return encoded;
    }

    public static String decodePacket(byte[] data)
    {
        return decodePacket(data, 0, data.length);
    }

    public static String decodePacket(byte[] data, int offset, int length)
    {
        if (data == null || length < 4 || length > MAX_PACKET_BYTES
                || offset < 0 || offset + length > data.length)
        {
            throw new IllegalArgumentException("invalid packet bounds");
        }

        ByteBuffer input = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN);
        if (Short.toUnsignedInt(input.getShort()) != PACKET_MAGIC)
        {
            throw new IllegalArgumentException("invalid packet magic");
        }

        int opcodeId = Byte.toUnsignedInt(input.get());
        int fieldCount = Byte.toUnsignedInt(input.get());
        PacketRegistry.Spec spec = PacketRegistry.byId(opcodeId);
        if (spec == null || fieldCount > MAX_FIELDS)
        {
            throw new IllegalArgumentException("invalid packet registry entry");
        }

        StringBuilder output = new StringBuilder(Math.max(24, length));
        output.append(spec.opcode);
        for (int i = 0; i < fieldCount; i++)
        {
            output.append('\t');
            appendEscaped(output, readField(input));
        }

        if (input.hasRemaining())
        {
            throw new IllegalArgumentException("trailing packet bytes");
        }
        return output.toString();
    }

    public static List<String> decodeDatagram(byte[] data, int length)
    {
        if (data == null || length < 2 || length > data.length)
        {
            throw new IllegalArgumentException("invalid datagram");
        }

        ByteBuffer input = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);
        int magic = Short.toUnsignedInt(input.getShort());
        if (magic == PACKET_MAGIC)
        {
            List<String> one = new ArrayList<>(1);
            one.add(decodePacket(data, 0, length));
            return one;
        }
        if (magic != BATCH_MAGIC)
        {
            throw new IllegalArgumentException("invalid datagram magic");
        }

        List<String> packets = new ArrayList<>();
        while (input.hasRemaining())
        {
            if (input.remaining() < 2)
            {
                throw new IllegalArgumentException("truncated batch length");
            }
            int packetLength = Short.toUnsignedInt(input.getShort());
            if (packetLength < 4 || packetLength > input.remaining())
            {
                throw new IllegalArgumentException("invalid batch packet length");
            }
            int packetOffset = input.position();
            packets.add(decodePacket(data, packetOffset, packetLength));
            input.position(packetOffset + packetLength);
        }
        return packets;
    }

    private static void writeField(ByteArrayOutputStream output, String value)
    {
        Integer intValue = canonicalInt(value);
        if (intValue != null)
        {
            output.write(TYPE_INT32);
            writeVarLong(output, ((long) intValue << 1) ^ (intValue >> 31));
            return;
        }

        Long longValue = canonicalLong(value);
        if (longValue != null)
        {
            output.write(TYPE_INT64);
            writeVarLong(output, (longValue << 1) ^ (longValue >> 63));
            return;
        }

        Float floatValue = canonicalFloat(value);
        if (floatValue != null)
        {
            output.write(TYPE_FLOAT32);
            writeInt(output, Float.floatToRawIntBits(floatValue));
            return;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_BYTES)
        {
            throw new IllegalArgumentException("field too large");
        }
        if (bytes.length <= 254)
        {
            output.write(TYPE_STRING);
            output.write(bytes.length);
        }
        else
        {
            output.write(TYPE_STRING16);
            writeShort(output, bytes.length);
        }
        output.write(bytes, 0, bytes.length);
    }

    private static String readField(ByteBuffer input)
    {
        if (!input.hasRemaining())
        {
            throw new IllegalArgumentException("truncated field");
        }

        int type = Byte.toUnsignedInt(input.get());
        if (type == TYPE_INT32)
        {
            long raw = readVarLong(input);
            return Integer.toString((int) ((raw >>> 1) ^ -(raw & 1L)));
        }
        if (type == TYPE_INT64)
        {
            long raw = readVarLong(input);
            return Long.toString((raw >>> 1) ^ -(raw & 1L));
        }
        if (type == TYPE_FLOAT32)
        {
            require(input, 4);
            return Float.toString(Float.intBitsToFloat(input.getInt()));
        }
        int length;
        if (type == TYPE_STRING)
        {
            require(input, 1);
            length = Byte.toUnsignedInt(input.get());
        }
        else if (type == TYPE_STRING16)
        {
            require(input, 2);
            length = Short.toUnsignedInt(input.getShort());
        }
        else
        {
            throw new IllegalArgumentException("unknown field type");
        }
        require(input, length);
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Integer canonicalInt(String value)
    {
        try
        {
            int parsed = Integer.parseInt(value);
            return Integer.toString(parsed).equals(value) ? parsed : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static Long canonicalLong(String value)
    {
        try
        {
            long parsed = Long.parseLong(value);
            return Long.toString(parsed).equals(value) ? parsed : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static Float canonicalFloat(String value)
    {
        if (value == null || value.indexOf('.') < 0 || value.indexOf('e') >= 0 || value.indexOf('E') >= 0)
        {
            return null;
        }
        try
        {
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) && Float.toString(parsed).equals(value) ? parsed : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static List<String> splitEscaped(String text)
    {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++)
        {
            char value = text.charAt(i);
            if (escaped)
            {
                if (value == 't') current.append('\t');
                else if (value == 'n') current.append('\n');
                else if (value == 'r') current.append('\r');
                else current.append(value);
                escaped = false;
            }
            else if (value == '\\')
            {
                escaped = true;
            }
            else if (value == '\t')
            {
                parts.add(current.toString());
                current.setLength(0);
            }
            else
            {
                current.append(value);
            }
        }
        if (escaped)
        {
            current.append('\\');
        }
        parts.add(current.toString());
        return parts;
    }

    private static void appendEscaped(StringBuilder output, String value)
    {
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '\\') output.append("\\\\");
            else if (c == '\t') output.append("\\t");
            else if (c == '\n') output.append("\\n");
            else if (c == '\r') output.append("\\r");
            else output.append(c);
        }
    }

    private static void require(ByteBuffer input, int bytes)
    {
        if (bytes < 0 || input.remaining() < bytes)
        {
            throw new IllegalArgumentException("truncated packet");
        }
    }

    private static void writeVarLong(ByteArrayOutputStream output, long value)
    {
        while ((value & ~0x7fL) != 0L)
        {
            output.write((int) ((value & 0x7fL) | 0x80L));
            value >>>= 7;
        }
        output.write((int) value);
    }

    private static long readVarLong(ByteBuffer input)
    {
        long value = 0L;
        for (int shift = 0; shift < 64; shift += 7)
        {
            require(input, 1);
            int current = Byte.toUnsignedInt(input.get());
            value |= (long) (current & 0x7f) << shift;
            if ((current & 0x80) == 0)
            {
                return value;
            }
        }
        throw new IllegalArgumentException("invalid varint");
    }

    private static void writeShort(ByteArrayOutputStream output, int value)
    {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, int value)
    {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

}
