/**
 * 
 */
package org.apache.hadoop.mapred.buffer;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TaskID;
import org.apache.hadoop.mapred.buffer.net.BufferExchange;
import org.apache.hadoop.mapred.buffer.net.BufferRequest;
import org.apache.hadoop.mapred.buffer.net.BufferExchange.BufferType;
import org.apache.hadoop.mapred.buffer.net.BufferExchange.Connect;
import org.apache.hadoop.mapred.buffer.net.BufferExchange.Transfer;

/**
 * @author Jinglei Ren
 *
 */
public class FileWritable extends OutputFileWritable {
	
	private static final Log LOG = LogFactory.getLog(FileWritable.class.getName());
	
	private Map<TaskID, Integer> cursor;
	/**
	 * @see org.apache.hadoop.mapred.buffer.net.BufferExchangeSource.FileSource
	 * */
	public FileWritable(FileSystem rfs, JobConf conf, BufferRequest request) {
		super(rfs, conf, request);
		this.cursor = new HashMap<TaskID, Integer>();
	}
	
	/**
	 * @see org.apache.hadoop.mapred.buffer.net.BufferExchangeSource.FileSource#transfer(OutputFile file)
	 * */
	@Override
	public long write(SocketChannel channel) throws IOException {
	//	OutputFile.FileHeader header = (OutputFile.FileHeader) file.header();
		OutputFile.FileHeader header = (OutputFile.FileHeader) this.header; //inherited from OutputFile
		
		TaskID taskid = header.owner().getTaskID();
		if (!cursor.containsKey(taskid) || cursor.get(taskid) == header.ids().first()) { 
			BufferExchange.Connect result = open(channel.socket(), BufferType.FILE);
			if (result == Connect.OPEN) {
			//	LOG.debug("Transfer file " + file + ". Destination " + destination());
				LOG.debug("Transfer file " + this + ". Destination " + this.destination);
			//	Transfer response = transmit(file);
				Transfer response = transmit();
				if (response == Transfer.TERMINATE) {
				//	return Transfer.TERMINATE;
					return OutputFileWritable.TERMINATE;
				}

				/* Update my next cursor position. */
				int position = header.ids().last() + 1;
				try { 
					int next = istream.readInt();
					if (position != next) {
						LOG.debug("Assumed next position " + position + " != actual " + next);
						position = next;
					}
				} catch (IOException e) { e.printStackTrace(); LOG.error(e); }

				if (response == Transfer.SUCCESS) {
					if (header.eof()) {
						LOG.debug("Transfer end of file for source task " + taskid);
					//	close(); // Notice that it does not refer to OutputFile.close().
					}
					cursor.put(taskid, position);
				//	LOG.debug("Transfer complete. New position " + cursor.get(taskid) + ". Destination " + destination());
					LOG.debug("Transfer complete. New position " + cursor.get(taskid) + ". Destination " + this.destination);
					return OutputFileWritable.SUCCESS;
				} else if (response == Transfer.IGNORE){
					cursor.put(taskid, position); // Update my cursor position
					return OutputFileWritable.IGNORE;
				} else {
					LOG.debug("Unsuccessful send. Transfer response: " + response);
					return OutputFileWritable.ERROR;
				}
			//	return response;
			} else if (result == Connect.BUFFER_COMPLETE) {
				cursor.put(taskid, Integer.MAX_VALUE);
			//	return Transfer.SUCCESS;
				return OutputFileWritable.SUCCESS;
			} else {
			//	return Transfer.RETRY;
				return OutputFileWritable.RETRY;
			}
		}
		else {
			LOG.debug("Transfer ignore header " + header + " current position " + cursor.get(taskid));
		//	return Transfer.IGNORE;
			return OutputFileWritable.IGNORE;
		}
	}

	@Override
	public long read(SocketChannel channel) throws IOException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void setValue(Object value) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Object getValue() {
		// TODO Auto-generated method stub
		return null;
	}
}