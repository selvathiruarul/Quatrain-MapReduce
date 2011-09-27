/**
 * 
 */
package org.stanzax.quatrain.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.stanzax.quatrain.io.ChannelWritable;
import org.stanzax.quatrain.io.EOR;
import org.stanzax.quatrain.io.Log;
import org.stanzax.quatrain.io.Writable;

/**
 * Container of multiple returns
 */
public abstract class ReplySet {
    
    private ReplySet(long timeout) {
        this.timeout = timeout;
    }
    
    public void close() {
        if (callID == 0) return;
        waiting.remove(callID);
    }
    
    public void finalize() throws Throwable {
        super.finalize();
        close();
    }
    
    /**
     * Register for awaiting reply. 
     * Invoked before sending request to guarantee no reply omitted.
     */
    public void register(int callID) {
        this.callID = callID;
        waiting.put(callID, this);
        if (Log.DEBUG) Log.action("New result set is registered, .current total #", waiting.size());
    }
    
    public boolean timedOut() {
        return timedOut;
    }
    
    public boolean hasError() {
        return errors.size() > 0;
    }
    
    /** Whether this set contains all expected results including possible errors */
    public boolean isPartial() {
        return !done;
    }
    
    public void putError(String errorMessage) {
        errors.add(errorMessage);
    }
    
    public String errorMessage() {
        StringBuilder errorInfo = new StringBuilder();
        for (String error : errors) {
            errorInfo.append("[QUATRAIN]");
            errorInfo.append(error);
        }
        return errorInfo.toString();
    }
    
    /**
     * Test whether new element it ready in non-blocking manner
     * */
    public synchronized boolean isReady() {
        return buffer != null 
            || (done && replyQueue.size() > 1)
            || (!done && replyQueue.size() > 0);
    }
    
    /**
     * Tell whether nextElement() would safely return an element
     * */
    public boolean hasMore() {
        if (isReady()) {
            return true;
        } else return tryNext();
    }

    public Object nextElement() {
        if (buffer != null || tryNext()) {
            try {
                return buffer;
            } finally {
                buffer = null;
            }
        } else return null;
    }
    
    public synchronized void putEnd() {
        replyQueue.add(new EOR());
        done = true;
        if (Log.DEBUG) Log.action("[ReplySet] Call # reaches reply end.", callID);
    }
    
    /**
     * Try to retrieve an additional element and store it in {@link#buffer}.
     * The {@link#buffer} would be overwritten, 
     * so test whether it is null before invoking this method. <br>
     * <p>This is the single point where {@link#replyQueue} is dequeued.
     * */
    private boolean tryNext() {
        if (!timedOut) try {
            Object next = replyQueue.poll(timeout, TimeUnit.MILLISECONDS);
            if (next == null) timedOut = true;
            else if (next instanceof EOR) next = null;
            buffer = next;
            return buffer != null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }
    
    protected int callID = 0;
    protected long timeout;
    protected LinkedBlockingQueue<Object> replyQueue = new LinkedBlockingQueue<Object>();
    private Object buffer = null;
    private Vector<String> errors = new Vector<String>();
    /** Indicates whether the EOR is enqueued. */
    private volatile boolean done = false;
    /** Indicates whether additional elements should be retrieved */
    protected volatile boolean timedOut = false;
    
    public static ReplySet get(int callID) {
        return waiting.get(callID);
    }
    
    private static ConcurrentHashMap<Integer, ReplySet> waiting = 
        new ConcurrentHashMap<Integer, ReplySet>();
    
    /**
     * @param source - Data source, whose specific form is determined by implementation
     * */
    public abstract boolean putData(Object source);
    
    public static final int INTERNAL = 0, EXTERNAL = 1, ERROR = -1;
    
    public static class Internal extends ReplySet {
        public Internal(Writable type, long timeout) {
            super(timeout);
            this.type = type;
        }
        
        /** 
         * Input should only contain data entries
         * @return false if any exception happens, true otherwise.
        */
        public boolean putData(Object source) {
            DataInputStream istream = (DataInputStream) source;
            if (!timedOut) { // otherwise no additional elements would be retrieved,
                             // so there would be no meaning to put in new data
                try {
                    while (istream.available() > 0) {
                        type.readFields(istream);
                        replyQueue.add(type.getValue());
                        if (Log.DEBUG) Log.action("[ReplySet] Call # read in data.", 
                                callID, type.getValue());
                    }
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }
        
        private Writable type;
    }
    
    public static class External extends ReplySet {
        
        public External(ChannelWritable writable, long timeout) {
            super(timeout);
            this.writable = writable;
        }
        
        @Override
        public boolean putData(Object source) {
            SocketChannel channel = (SocketChannel)source;
            if (!timedOut) {
                try {
                    writable.read(channel);
                    replyQueue.add(writable.getValue());
                    if (Log.DEBUG) Log.action("[ReplySet] Call # read in data and register a in-memory representative.", 
                            callID, writable.getValue());
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return false;
        }
        
        private ChannelWritable writable;
    }
}