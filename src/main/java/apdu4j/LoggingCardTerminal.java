/**
 * Copyright (c) 2014-2017 Martin Paljak
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j;

import javax.smartcardio.*;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;

/**
 * Log everything going through this CardTerminal, also hypothetical SCard API calls for connect/disconnect.
 * <p>
 * Dump APDU-s to a file for automatic playback and inspection
 *
 * @author Martin Paljak
 */
public class LoggingCardTerminal extends CardTerminal {
    // The actual terminal
    protected final CardTerminal terminal;
    protected final PrintStream log;
    protected final PrintStream dump;

    private LoggingCardTerminal(CardTerminal term, PrintStream log, PrintStream dump) {
        this.terminal = term;
        this.log = log;
        this.dump = dump;
    }

    private static LoggingCardTerminal make(CardTerminal term, OutputStream log, OutputStream dump) {
        final PrintStream logstream;
        final PrintStream dumpstream;
        try {
            logstream = new PrintStream(log, true, StandardCharsets.UTF_8.name());
            if (dump != null) {
                dumpstream = new PrintStream(dump, true, StandardCharsets.UTF_8.name());
            } else
                dumpstream = null;
        } catch (UnsupportedEncodingException e) {
            throw new Error("Must support UTF-8", e);
        }
        return new LoggingCardTerminal(term, logstream, dumpstream);
    }

    public static LoggingCardTerminal getInstance(CardTerminal term) {
        return make(term, System.out, null);
    }

    public static LoggingCardTerminal getInstance(CardTerminal term, OutputStream dump) {
        return make(term, System.out, dump);
    }

    @Override
    public Card connect(String arg0) throws CardException {
        return new LoggingCard(terminal, arg0);
    }

    @Override
    public String getName() {
        return terminal.getName();
    }

    @Override
    public boolean isCardPresent() throws CardException {
        return terminal.isCardPresent();
    }

    @Override
    public boolean waitForCardAbsent(long arg0) throws CardException {
        return terminal.waitForCardAbsent(arg0);

    }

    @Override
    public boolean waitForCardPresent(long arg0) throws CardException {
        return terminal.waitForCardPresent(arg0);
    }


    public final class LoggingCard extends Card {
        private long inBytes = 0;
        private long outBytes = 0;
        private final Card card;

        private LoggingCard(CardTerminal term, String protocol) throws CardException {
            log.print("SCardConnect(\"" + terminal.getName() + "\", " + (protocol.equals("*") ? "T=*" : protocol) + ")");
            log.flush();
            try {
                card = terminal.connect(protocol);
                String atr = HexUtils.bin2hex(card.getATR().getBytes());
                log.println(" -> " + card.getProtocol() + ", " + atr);
                if (dump != null) {
                    String ts = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z").format(Calendar.getInstance().getTime());
                    dump.println("# Generated on " + ts + " by apdu4j/" + SCTool.getVersion());
                    dump.println("# Using " + terminal.getName());
                    dump.println("# ATR: " + atr);
                    dump.println("# PROTOCOL: " + card.getProtocol());
                    dump.println("#");
                }
            } catch (CardException e) {
                log.println(" -> " + TerminalManager.getExceptionMessage(e));
                throw e;
            }
        }

        @Override
        public void beginExclusive() throws CardException {
            log.println("SCardBeginTransaction(\"" + terminal.getName() + "\")");
            card.beginExclusive();
        }

        @Override
        public void disconnect(boolean arg0) throws CardException {
            log.println("SCardDisconnect(\"" + terminal.getName() + "\", " + arg0 + ") tx:" + outBytes + "/rx:" + inBytes);
            inBytes = outBytes = 0;
            if (dump != null) {
                dump.close();
            }
            card.disconnect(arg0);
        }

        @Override
        public void endExclusive() throws CardException {
            log.println("SCardEndTransaction(\"" + terminal.getName() + "\")");
            card.endExclusive();
        }

        @Override
        public ATR getATR() {
            return card.getATR();
        }

        @Override
        public CardChannel getBasicChannel() {
            return new LoggingCardChannel(card);
        }

        @Override
        public String getProtocol() {
            return card.getProtocol();
        }

        @Override
        public CardChannel openLogicalChannel() throws CardException {
            throw new CardException("Logical channels are not supported");
        }

        @Override
        public byte[] transmitControlCommand(int arg0, byte[] arg1) throws CardException {
            log.print("SCardControl(\"" + terminal.getName() + "\", " + Integer.toString(arg0, 16) + ", " + HexUtils.bin2hex(arg1) + ")");
            final byte[] result;
            try {
                result = card.transmitControlCommand(arg0, arg1);
            } catch (CardException e) {
                String err = TerminalManager.getExceptionMessage(e);
                if (err != null) {
                    log.println("-> " + err);
                } else {
                    log.println("-> Exception");
                }
                throw e;
            }
            log.println("-> " + HexUtils.bin2hex(result));
            return result;
        }

        public final class LoggingCardChannel extends CardChannel {
            private final CardChannel channel;
            private final Card card;

            public LoggingCardChannel(Card card) {
                this.card = card;
                this.channel = card.getBasicChannel();
            }

            @Override
            public void close() throws CardException {
                channel.close();
            }

            @Override
            public Card getCard() {
                return card;
            }

            @Override
            public int getChannelNumber() {
                return channel.getChannelNumber();
            }

            @Override
            public ResponseAPDU transmit(CommandAPDU apdu) throws CardException {
                byte[] cb = apdu.getBytes();
                int len_end = apdu.getData().length > 255 ? 7 : 5;
                log.print("A>> " + card.getProtocol() + " (4+" + String.format("%04d", apdu.getData().length) + ")");
                log.print(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, 0, 4)));

                // Only if Case 2, 3 or 4 APDU
                if (cb.length > 4) {
                    int cmdlen = cb[ISO7816.OFFSET_LC] & 0xFF;

                    // If extended length
                    if (cmdlen == 0 && apdu.getData().length > 255) {
                        cmdlen = ((cb[ISO7816.OFFSET_LC + 1] & 0xff << 8) | cb[ISO7816.OFFSET_LC + 2] & 0xff);
                    }
                    // P3 is Le
                    if (apdu.getData().length < cmdlen) {
                        cmdlen = 0;
                    }
                    // print length bytes
                    log.print(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, 4, len_end)));
                    // print payload
                    log.print(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, len_end, len_end + cmdlen)));
                    // Print Le
                    if (len_end + cmdlen < cb.length) {
                        log.println(" " + HexUtils.bin2hex(Arrays.copyOfRange(cb, len_end + cmdlen, cb.length)));
                    } else {
                        log.println();
                    }
                } else {
                    log.println();
                }
                log.flush();

                long t = System.currentTimeMillis();
                final ResponseAPDU response;
                try {
                    response = channel.transmit(apdu);
                    outBytes += cb.length;
                } catch (CardException e) {
                    String err = TerminalManager.getExceptionMessage(e);
                    if (err != null)
                        System.out.println("<< " + err);
                    else
                        System.out.println("<< Exception");
                    throw e;
                }
                long ms = System.currentTimeMillis() - t;
                String time = ms + "ms";
                if (ms > 1000) {
                    time = ms / 1000 + "s" + ms % 1000 + "ms";
                }
                byte[] rb = response.getBytes();
                inBytes += rb.length;
                System.out.print("A<< (" + String.format("%04d", response.getData().length) + "+2) (" + time + ")");
                if (rb.length > 2) {
                    log.print(" " + HexUtils.bin2hex(Arrays.copyOfRange(rb, 0, rb.length - 2)));
                }
                log.println(" " + HexUtils.bin2hex(Arrays.copyOfRange(rb, rb.length - 2, rb.length)));
                if (dump != null) {
                    dump.println("# Sent\n" + HexUtils.bin2hex(cb));
                    dump.println("# Received in " + time + "\n" + HexUtils.bin2hex(rb));
                }
                return response;
            }

            @Override
            public int transmit(ByteBuffer cmd, ByteBuffer rsp) throws CardException {
                byte[] commandBytes = new byte[cmd.remaining()];
                cmd.get(commandBytes);
                cmd.position(0);

                log.println("B>> " + card.getProtocol() + " (" + commandBytes.length + ") " + HexUtils.bin2hex(commandBytes));
                int response = channel.transmit(cmd, rsp);
                outBytes += commandBytes.length;
                byte[] responseBytes = new byte[response];
                inBytes += responseBytes.length;
                rsp.get(responseBytes);
                rsp.position(0);
                log.println("B<< (" + responseBytes.length + ") " + HexUtils.bin2hex(responseBytes));
                if (dump != null) {
                    dump.println("# Sent\n" + HexUtils.bin2hex(commandBytes));
                    dump.println("# Received\n" + HexUtils.bin2hex(responseBytes));
                }
                return response;
            }
        }
    }
}
