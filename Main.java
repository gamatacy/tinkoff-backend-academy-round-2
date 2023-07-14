import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

// System.out.print(String.format("%02X ", b));

public class Main {

    static String address = "http://localhost:9998";
    static int hubSrc = 819;
    static int serial = 0;
    static HttpURLConnection httpURLConnection;
    static List<Payload> activeDevices;

    public static HttpURLConnection openHttpConnection() throws IOException {
        URL url = new URL(address);
        HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setDoOutput(true);
        return httpURLConnection;
    }

    public static void main(String[] args) {


        try {
            address = args[0];
            hubSrc = Integer.parseInt(args[1], 16);

            activeDevices = Command.whoIsHere();


            while (true) {
                for (int i = 0; i < activeDevices.size(); ++i) {
                    List<Payload> response = Command.getStatus(activeDevices.get(i));

                    if (response == null) continue;

                    for (Payload res : response) DeviceHandler.handle(res);
                }
            }

        } catch (NetworkException ne) {
            System.exit(99);
        } catch (EndOfDataException ee) {
            System.exit(0);
        } catch (Exception e) {
            System.exit(99);
        }
        System.exit(0);
    }
}

class Packet {
    private byte length;
    private byte[] payload;
    private byte crc8;

    public Packet(byte length, byte[] payload, byte src8) {
        this.length = length;
        this.payload = payload;
        this.crc8 = src8;
    }

    public byte getLength() {
        return length;
    }

    public byte[] getPayload() {
        return payload;
    }

    public byte getCrc8() {
        return crc8;
    }

    public byte[] toByteArray() {
        byte packetArray[] = new byte[length + 2];

        //Set payload length
        packetArray[0] = length;

        //Set payload
        for (int i = 1; i <= length; ++i) packetArray[i] = payload[i - 1];

        //Set CRC8
        packetArray[packetArray.length - 1] = crc8;

        return packetArray;
    }

}

class Payload {
    private int src;
    private int dst;
    private int serial;
    private byte devType;
    private byte cmd;
    private byte[] cmdBody;

    public Payload() {
    }

    public Payload(int src, int dst, int serial, byte devType, byte cmd, byte[] cmdBody) {
        this.src = src;
        this.dst = dst;
        this.serial = serial;
        this.devType = devType;
        this.cmd = cmd;
        this.cmdBody = cmdBody;
    }

    public int getSize() {
        byte uSrc[] = ULEB128Utils.intToULEB(src);
        byte uDst[] = ULEB128Utils.intToULEB(dst);
        byte uSerial[] = ULEB128Utils.intToULEB(serial);

        return uSrc.length + uDst.length + uSerial.length + 1 + 1 + cmdBody.length;
    }

    public byte[] toByteArray() {

        byte uSrc[] = ULEB128Utils.intToULEB(src);
        byte uDst[] = ULEB128Utils.intToULEB(dst);
        byte uSerial[] = ULEB128Utils.intToULEB(serial);

        int payloadLength = uSrc.length + uDst.length + uSerial.length + 1 + 1 + cmdBody.length;

        byte[] serializedPayload = new byte[payloadLength];
        int pointer = 0;

        for (byte b : uSrc) {
            serializedPayload[pointer] = b;
            pointer++;
        }

        for (byte b : uDst) {
            serializedPayload[pointer] = b;
            pointer++;
        }

        for (byte b : uSerial) {
            serializedPayload[pointer] = b;
            pointer++;
        }

        serializedPayload[pointer++] = devType;
        serializedPayload[pointer++] = cmd;

        for (byte b : cmdBody) {
            serializedPayload[pointer++] = b;
        }

        return serializedPayload;
    }

    public int getSrc() {
        return src;
    }

    public int getDst() {
        return dst;
    }

    public int getSerial() {
        return serial;
    }

    public byte getDevType() {
        return devType;
    }

    public byte getCmd() {
        return cmd;
    }

    public byte[] getCmdBody() {
        return cmdBody;
    }

    public void setSrc(int src) {
        this.src = src;
    }

    public void setDst(int dst) {
        this.dst = dst;
    }

    public void setSerial(int serial) {
        this.serial = serial;
    }

    public void setDevType(byte devType) {
        this.devType = devType;
    }

    public void setCmd(byte cmd) {
        this.cmd = cmd;
    }

    public void setCmdBody(byte[] cmdBody) {
        this.cmdBody = cmdBody;
    }

}

class PacketBuilder {
    public static List<Packet> buildPacketsFromByteArray(byte[] mergedPackets) {
        List<Packet> packets = new ArrayList<>();

        for (int i = 0; i < mergedPackets.length; i++) {

            byte payload[] = new byte[mergedPackets[i]];

            for (int j = 0; j < mergedPackets[i]; j++) payload[j] = mergedPackets[i + j + 1];

            Packet packet = new Packet(
                    mergedPackets[i],
                    payload,
                    mergedPackets[i + mergedPackets[i] + 1]
            );

            //if (validateCRC8(packet))
            packets.add(packet);

            //After +1 will be on CRC8, but after loop autoincrement will be on next length
            i += packet.getLength() + 1;

        }

        return packets;
    }

    private static boolean validateCRC8(Packet packet) {
        return packet.getCrc8() == CRC8.encode(packet.getPayload());
    }

}

class PayloadBuilder {

    //Create payloads list from packets list
    public static List<Payload> buildPayloadsFromPackets(List<Packet> packets) {
        List<Payload> payloads = new ArrayList<>();

        for (Packet packet : packets) {

            Payload payload = new Payload();

            int srcRaw[] = ULEB128Utils.ULEBToInt(
                    new byte[]{
                            packet.getPayload()[0],
                            packet.getPayload()[1]
                    }
            );

            payload.setSrc(srcRaw[0]);

            //Check if number length was 1 byte
            int index = 2;
            if (srcRaw[1] == 1) --index;

            int dstRaw[] = ULEB128Utils.ULEBToInt(
                    new byte[]{
                            packet.getPayload()[index],
                            packet.getPayload()[++index]
                    }
            );

            if (dstRaw[1] == 1) --index;
            payload.setDst(dstRaw[0]);

            int serialRaw[] = ULEB128Utils.ULEBToInt(
                    new byte[]{
                            packet.getPayload()[++index],
                            packet.getPayload()[index + 1]
                    }
            );

            if (serialRaw[1] == 0) ++index;
            payload.setSerial(serialRaw[0]);

            payload.setDevType(packet.getPayload()[++index]);
            payload.setCmd(packet.getPayload()[++index]);

            ++index;
            byte cmdBody[] = new byte[packet.getLength() - index];

            for (int i = 0; i < packet.getLength() - index; i++) {
                cmdBody[i] = packet.getPayload()[i + index];
            }

            payload.setCmdBody(cmdBody);

            //if (validatePayload(payload))
            payloads.add(payload);

        }

        return payloads;

    }

    private static boolean validatePayload(Payload payload) {

        if (payload.getSrc() > 0x3FFF || payload.getSrc() < 0) return false;
        else if (payload.getDst() > 0x3FFF || payload.getDst() < 0) return false;
        else if (payload.getSerial() < 1) return false;
        else if (payload.getDevType() < 1 || payload.getDevType() > 6) return false;

        return !((payload.getSrc() > 0x3FFF || payload.getSrc() < 0)
                || (payload.getDst() > 0x3FFF || payload.getDst() < 0)
                || (payload.getSerial() < 1)
                || (payload.getDevType() < 1 || payload.getDevType() > 6)
                || (payload.getCmd() < 1 || payload.getCmd() > 6));

    }

    public static Device buildDevice(Payload payload) {

        String name = StringUtils.parseTinkoffString(payload.getCmdBody());

        return new Device(
                name,
                Arrays.copyOfRange(
                        payload.getCmdBody(),
                        name.length(),
                        payload.getCmdBody().length
                )
        );

    }

    public static Device.EnvSensorProps buildEnvSensorProps(byte[] bytes) {

        Device.EnvSensorProps envSensor = new Device.EnvSensorProps();

        envSensor.setSensors(bytes[0]);
        envSensor.setTriggers(buildEndSensorPropsTriggers(bytes));

        return envSensor;

    }

    private static Device.EnvSensorProps.Trigger[] buildEndSensorPropsTriggers(byte[] bytes) {
        List<Device.EnvSensorProps.Trigger> triggers = new ArrayList<>();

        for (int i = 0; i < bytes.length; i++) {

            Device.EnvSensorProps.Trigger trigger = new Device.EnvSensorProps.Trigger();

            trigger.setOp(bytes[i]);

            ++i;

            int value[] = ULEB128Utils.ULEBToInt(
                    new byte[]{
                            bytes[i],
                            bytes[i + 1]
                    }
            );

            if (value[1] == 0) ++i;

            trigger.setValue(value[0]);

            trigger.setName(
                    StringUtils.parseTinkoffString(
                            Arrays.copyOfRange(bytes, i, bytes.length)
                    )
            );

            triggers.add(trigger);

            i += trigger.getName().length() - 1;

        }

        return triggers.toArray(new Device.EnvSensorProps.Trigger[triggers.size()]);
    }

    public static Device.EnvSensorStatus buildEnvSensorStatus(byte[] bytes) {

        Device.EnvSensorStatus envSensorStatus = new Device.EnvSensorStatus();
        List<Integer> values = new ArrayList<>();

        for (int i = 0; i < bytes.length; i++) {
            byte b[];

            if (i == bytes.length - 1) b = new byte[]{bytes[i]};
            else b = new byte[]{bytes[i], bytes[i + 1]};

            int value[] = ULEB128Utils.ULEBToInt(b);

            values.add(value[0]);

            if (value[1] == 1) ++i;

        }

        envSensorStatus.setValues(values.stream().mapToInt(i -> i).toArray());

        return envSensorStatus;
    }

    public static Device.SwitchProps buildSwitchProps(byte[] bytes) {
        List<String> strings = new ArrayList<>();
        String str = "";

        for (int i = 0; i < bytes.length; i += str.length() + 2) {
            str = StringUtils.parseTinkoffString(Arrays.copyOfRange(bytes, i, bytes.length));
            strings.add(str);
        }

        String s[] = strings.toArray(new String[strings.size()]);

        return new Device.SwitchProps(s);
    }

    public static Device.TimerCmdBody buildTimerCmdBody(byte[] bytes) {
        long timestamp[] = ULEB128Utils.ULEBToLong(bytes);
        return new Device.TimerCmdBody(timestamp[0]);
    }

}

class Command {

    public static List<Payload> handleResponse() throws IOException, EndOfDataException, NetworkException {
        long startTime = System.currentTimeMillis();
        int responseCode = Main.httpURLConnection.getResponseCode();

        switch (responseCode) {
            case 200:
                break;
            case 204:
                throw new EndOfDataException();
            default:
                throw new NetworkException();
        }

        long executionTime = System.currentTimeMillis() - startTime;

        if (executionTime > 300) return null;

        InputStream inputStream = Main.httpURLConnection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

        String response = reader.readLine();
        reader.close();
        inputStream.close();

        byte bytes[] = StringUtils.convertBase64ToBytes(response);

        List<Packet> packets = PacketBuilder.buildPacketsFromByteArray(bytes);
        List<Payload> payloads = PayloadBuilder.buildPayloadsFromPackets(packets);

//        payloads = payloads.stream()
//                .filter(payload -> payload.getCmd() != 6)
//                .collect(Collectors.toList());

        return payloads;
    }

    private static void sendData(String data) throws IOException {
        Main.serial++;
        Main.httpURLConnection = Main.openHttpConnection();
        OutputStream outputStream = Main.httpURLConnection.getOutputStream();
        outputStream.write(data.getBytes());
        outputStream.close();
    }

    public static List<Payload> whoIsHere() throws IOException, EndOfDataException, NetworkException {
        Main.serial++;

        Payload payload = new Payload(
                Main.hubSrc,
                0x3FFF,
                Main.serial,
                (byte) 1,
                (byte) 1,
                new byte[]{0}
        );

        Packet packet = new Packet(
                (byte) payload.getSize(),
                payload.toByteArray(),
                (byte) CRC8.encode(payload.toByteArray())
        );

        String data = StringUtils.convertBytesToBase64(packet.toByteArray());

        sendData(data);

        return handleResponse();

    }

    public static List<Payload> iAmHere() throws IOException, EndOfDataException, NetworkException {
        Device device = new Device("SmartHub");

        Payload payload = new Payload(
                Main.hubSrc,
                0x3FFF,
                Main.serial,
                (byte) 1,
                (byte) 2,
                device.getSerializedDevice()
        );

        Packet packet = new Packet(
                (byte) payload.getSize(),
                payload.toByteArray(),
                (byte) CRC8.encode(payload.toByteArray())
        );

        String data = StringUtils.convertBytesToBase64(packet.toByteArray());

        sendData(data);

        return handleResponse();

    }

    public static List<Payload> getStatus(Payload payload) throws IOException, EndOfDataException, NetworkException {
        Payload newPayload = new Payload(
                Main.hubSrc,
                payload.getSrc(),
                Main.serial,
                payload.getDevType(),
                (byte) 3,
                new byte[0]
        );

        Packet packet = new Packet(
                (byte) newPayload.getSize(),
                newPayload.toByteArray(),
                (byte) CRC8.encode(newPayload.toByteArray())
        );

        String data = StringUtils.convertBytesToBase64(packet.toByteArray());

        sendData(data);

        return handleResponse();

    }

    public static List<Payload> setStatus(Payload payload) throws IOException, EndOfDataException, NetworkException {

        Packet packet = new Packet(
                (byte) payload.getSize(),
                payload.toByteArray(),
                (byte) CRC8.encode(payload.toByteArray())
        );

        String data = StringUtils.convertBytesToBase64(packet.toByteArray());

        sendData(data);

        return handleResponse();
    }

}

class Device {
    private String devName;
    private byte devProps[];

    public Device(String devName, byte[] devProps) {
        this.devName = devName;
        this.devProps = devProps;
    }

    public Device(String devName) {
        this.devName = devName;
        this.devProps = new byte[0];
    }

    public byte[] getSerializedDevice() {
        byte device[] = new byte[devName.length() + 1 + devProps.length];
        byte str[] = StringUtils.convertToTinkoffString(devName);

        for (int i = 0; i < str.length; ++i) {
            device[i] = str[i];
        }

        for (int i = 0; i < devProps.length; ++i) {
            device[i + str.length] = devProps[i];
        }

        return device;

    }

    public String getDevName() {
        return devName;
    }

    public byte[] getDevProps() {
        return devProps;
    }

    static class EnvSensorProps implements DevicePayload {
        private byte sensors;
        private Trigger[] triggers;

        static class Trigger {
            private byte op;
            private int value;
            private String name;

            public byte getOp() {
                return op;
            }

            public void setOp(byte op) {
                this.op = op;
            }

            public int getValue() {
                return value;
            }

            public void setValue(int value) {
                this.value = value;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }
        }

        public void setSensors(byte sensors) {
            this.sensors = sensors;
        }

        public byte getSensors() {
            return sensors;
        }

        public Trigger[] getTriggers() {
            return triggers;
        }

        public void setTriggers(Trigger[] triggers) {
            this.triggers = triggers;
        }
    }

    static class EnvSensorStatus implements DevicePayload {
        private int values[];

        public int[] getValues() {
            return values;
        }

        public void setValues(int[] values) {
            this.values = values;
        }
    }

    static class SwitchProps implements DevicePayload {
        String names[];

        public SwitchProps() {
        }

        public SwitchProps(String[] names) {
            this.names = names;
        }

        public String[] getNames() {
            return names;
        }

        public void setNames(String[] names) {
            this.names = names;
        }

    }

    static class SwitchStatus implements DevicePayload {
        byte status;

        public SwitchStatus(byte status) {
            this.status = status;
        }

        byte getStatus() {
            return this.status;
        }

        public void setStatus(byte status) {
            this.status = status;
        }
    }

    static class OthersStatus implements DevicePayload {
        byte status;

        public OthersStatus() {
        }

        public byte getStatus() {
            return status;
        }

        public void setStatus(byte status) {
            this.status = status;
        }

    }

    static class TimerCmdBody implements DevicePayload {
        private long timestamp;

        public TimerCmdBody(long timestamp) {
            this.timestamp = timestamp;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }


}

class DeviceHandler {

    public static void handle(Payload payload) throws EndOfDataException, IOException, NetworkException {

        if (payload.getDevType() == 6) return;

        switch (payload.getCmd()) {
            case 1: {
                List<Payload> payloads = Command.iAmHere();
                for (Payload p : payloads) {
                    addDevice(p);
                }
                break;
            }
            case 2: {
                addDevice(payload);
                break;
            }
            case 4: {
                handleStatus(payload);
            }
        }

    }

    private static void handleStatus(Payload payload) throws EndOfDataException, IOException, NetworkException {
        switch (payload.getDevType()) {
            case 2: {
                handleEnvSensor(payload);
                break;
            }
            case 3: {
                handleSwitch(payload);
                break;
            }
            case 4: {
                handleLamp();
                break;
            }
            case 5: {
                handleSocket();
            }
        }
    }

    private static void handleEnvSensor(Payload payload) throws EndOfDataException, IOException, NetworkException {
        Device.EnvSensorStatus sensorStatus = PayloadBuilder.buildEnvSensorStatus(payload.getCmdBody());
        int sensorValues[] = new int[4];

        Device.EnvSensorProps sensorProps = new Device.EnvSensorProps();

        for (Payload p : Main.activeDevices) {
            if (p.getSrc() == payload.getSrc() && p.getCmd() == 2)
                sensorProps = PayloadBuilder.buildEnvSensorProps(p.getCmdBody());
        }

        int i = 0;
        if ((sensorProps.getSensors() & 0001) == 0) sensorValues[0] = 0;
        else {
            sensorValues[0] = sensorStatus.getValues()[i];
            ++i;
        }
        if ((sensorProps.getSensors() & 0010) == 0) sensorValues[1] = 0;
        else {
            sensorValues[1] = sensorStatus.getValues()[i];
            ++i;
        }
        if ((sensorProps.getSensors() & 0100) == 0) sensorValues[2] = 0;
        else {
            sensorValues[2] = sensorStatus.getValues()[i];
            ++i;
        }
        if ((sensorProps.getSensors() & 1000) == 0) sensorValues[3] = 0;
        else {
            sensorValues[3] = sensorStatus.getValues()[i];
        }

        boolean result;

        Payload status = new Payload();
        status.setSrc(Main.hubSrc);
        status.setSerial(Main.serial);
        status.setCmd((byte) 5);
        for (Device.EnvSensorProps.Trigger trigger : sensorProps.getTriggers()) {
            switch (EnvSensorUtils.getSensorNumber(trigger.getOp())) {
                case 0: {
                    result = EnvSensorUtils.checkTemperature(
                            sensorStatus.getValues()[0],
                            trigger.getValue(),
                            (trigger.getOp() & 0010) == 1
                    );

                    if (result) {
                        Payload p = findByName(trigger.getName());

                        status.setDst(p.getSrc());
                        status.setDevType(p.getDevType());
                        status.setCmdBody(new byte[]{(byte) (trigger.getOp() & 0001)});

                        Command.setStatus(status);
                    }

                }
                case 1: {
                    result = EnvSensorUtils.checkWet(
                            sensorStatus.getValues()[1],
                            trigger.getValue(),
                            (trigger.getOp() & 0010) == 1
                    );

                    if (result) {
                        Payload p = findByName(trigger.getName());

                        status.setDst(p.getSrc());
                        status.setDevType(p.getDevType());
                        status.setCmdBody(new byte[]{(byte) (trigger.getOp() & 0001)});

                        Command.setStatus(status);
                    }

                }
                case 2: {
                    result = EnvSensorUtils.checkLight(
                            sensorStatus.getValues()[2],
                            trigger.getValue(),
                            (trigger.getOp() & 0010) == 1
                    );

                    if (result) {
                        Payload p = findByName(trigger.getName());

                        status.setDst(p.getSrc());
                        status.setDevType(p.getDevType());
                        status.setCmdBody(new byte[]{(byte) (trigger.getOp() & 0001)});

                        Command.setStatus(status);
                    }

                }
                case 3: {
                    result = EnvSensorUtils.checkAir(
                            sensorStatus.getValues()[3],
                            trigger.getValue(),
                            (trigger.getOp() & 0010) == 1
                    );

                    if (result) {
                        Payload p = findByName(trigger.getName());

                        status.setDst(p.getSrc());
                        status.setDevType(p.getDevType());
                        status.setCmdBody(new byte[]{(byte) (trigger.getOp() & 0001)});

                        Command.setStatus(status);
                    }

                }
            }
        }

    }

    private static void handleSwitch(Payload payload) throws EndOfDataException, IOException, NetworkException {
        Device.SwitchStatus switchStatus = new Device.SwitchStatus(payload.getCmdBody()[0]);

        Device.SwitchProps props = new Device.SwitchProps(new String[0]);

        for (Payload p : Main.activeDevices) {
            if (p.getSrc() == payload.getSrc() && p.getCmd() == 2) {
                props = PayloadBuilder.buildSwitchProps(p.getCmdBody());
                break;
            }
        }

        if (props.getNames().length == 0) return;

        String names[] = Arrays.copyOfRange(props.getNames(), 1, props.getNames().length);

        for (Payload p : Main.activeDevices) {
            if (p.getCmd() == 2) {
                for (int i = 0; i < names.length; i++) {

                    if (Objects.equals(StringUtils.parseTinkoffString(p.getCmdBody()), names[i])) {
                        byte cmdBody[] = new byte[1];
                        cmdBody[0] = switchStatus.getStatus();

                        Payload status = new Payload(
                                Main.hubSrc,
                                p.getSrc(),
                                Main.serial,
                                p.getDevType(),
                                (byte) 5,
                                cmdBody
                        );

                        Command.setStatus(status);

                    }

                }
            }
        }

    }

    private static void handleLamp() {
    }

    private static void handleSocket() {
    }

    public static void addDevice(Payload payload) {
        boolean isNew = true;

        for (Payload p : Main.activeDevices) {
            if (p.getSrc() == payload.getSrc() && payload.getCmd() == 2) isNew = false;
        }

        if (isNew) Main.activeDevices.add(payload);
    }

    public static Payload findByName(String name) {
        for (Payload p : Main.activeDevices) {
            if (p.getCmd() == 2) {
                String deviceName = StringUtils.parseTinkoffString(p.getCmdBody());
                if (name == deviceName) return p;
            }
        }
        return null;
    }

}

class ULEB128Utils {

    public static byte[] intToULEB(int value) {
        List<Byte> result = new ArrayList<>();

        do {
            byte byte_ = (byte) (value & 0x7F);
            value >>= 7;

            if (value != 0) {
                byte_ |= (byte) 0x80;
            }

            result.add(byte_);
        } while (value != 0);

        byte[] byteArray = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) {
            byteArray[i] = result.get(i);
        }
        return byteArray;
    }

    public static int[] ULEBToInt(byte[] bytes) {
        int result = 0;
        int shift = 0;
        int pair[] = new int[2];
        pair[1] = 0;

        for (byte b : bytes) {
            int value = b & 0x7F;
            result |= (value << shift);
            shift += 7;

            if ((b & 0x80) == 0) {
                if (shift == 7) pair[1] = 1;
                break;
            }
        }


        pair[0] = result;
        return pair;
    }

    public static long[] ULEBToLong(byte[] bytes) {
        long result = 0;
        int shift = 0;
        long pair[] = new long[2];
        pair[1] = 0;

        for (byte b : bytes) {
            int value = b & 0x7F;
            result |= (value << shift);
            shift += 7;

            if ((b & 0x80) == 0) {
                if (shift == 7) pair[1] = 1;
                break;
            }
        }


        pair[0] = result;
        return pair;
    }


}

class StringUtils {


    public static byte[] convertToTinkoffString(String str) {
        byte length = (byte) str.length();
        byte tinkoffString[] = new byte[length + 1];

        tinkoffString[0] = length;

        for (int i = 0; i < length; ++i) {
            tinkoffString[i + 1] = (byte) str.charAt(i);
        }

        return tinkoffString;
    }

    public static String parseTinkoffString(byte[] str) {
        byte length = str[0];
        String normalString = "";

        for (int i = 1; i <= length; ++i) {
            normalString += (char) str[i];
        }

        return normalString;
    }

    public static String convertBase64(String url) {
        return url.replace("_", "/").replace("-", "+");
    }

    public static byte[] convertBase64ToBytes(String base) {
        String response = StringUtils.convertBase64(base);
        return Base64.getDecoder().decode(response);
    }

    public static String convertBytesToBase64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}

class CRC8 {
    private static final int POLYNOMIAL = 0x1D;

    public static int encode(byte[] data) {
        int crc = 0x00;

        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (crc << 1) ^ POLYNOMIAL;
                } else {
                    crc <<= 1;
                }
            }
        }

        return crc & 0xFF;
    }

}

class EndOfDataException extends Exception {
}

class NetworkException extends Exception {
}

class EnvSensorUtils {

    public static byte getSensorNumber(byte b) {
        int val = (int) b >> 2;
        return (byte) (val & 0011);
    }

    public static boolean checkTemperature(int value, int edge, boolean isBiggerComparsion) {
        if (isBiggerComparsion) return value > edge;
        else return value < edge;
    }

    public static boolean checkWet(int value, int edge, boolean isBiggerComparsion) {
        if (isBiggerComparsion) return value > edge;
        else return value < edge;
    }

    public static boolean checkLight(int value, int edge, boolean isBiggerComparsion) {
        if (isBiggerComparsion) return value > edge;
        else return value < edge;
    }

    public static boolean checkAir(int value, int edge, boolean isBiggerComparsion) {
        if (isBiggerComparsion) return value > edge;
        else return value < edge;
    }

}

interface DevicePayload { }