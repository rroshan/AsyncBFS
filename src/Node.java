import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;

public class Node 
{
	private int id;
	private ArrayList<NodeNeighbor> neighbors;
	private InetSocketAddress address; //my address
	private static final int MESSAGE_SIZE = 100;
	private boolean explorePhase = false;
	private int parentId = Integer.MIN_VALUE;
	private Object parentLock = new Object();
	private Object sendLock = new Object();
	private Object recvLock = new Object();
	private Logger logger = Logger.getLogger("MyLog");
	private int lengthFromRoot = Integer.MAX_VALUE;
	private CopyOnWriteArrayList<Integer> oldParentList = new CopyOnWriteArrayList<Integer>();
	private CopyOnWriteArrayList<Integer> childrenList = new CopyOnWriteArrayList<Integer>();

	//node inner class
	class NodeNeighbor
	{
		public int neighborId;
		public InetSocketAddress neighborAddress;
		public SctpChannel sctpChannel;

		public NodeNeighbor(int neighborId, InetSocketAddress neighborAddress)
		{
			this.neighborId = neighborId;
			this.neighborAddress = neighborAddress;
		}

		public InetSocketAddress getNeighborAddress()
		{
			return neighborAddress;
		}

		public void setSctpChannel(SctpChannel sctpChannel)
		{
			this.sctpChannel = sctpChannel;
		}

		public SctpChannel getSctpChannel()
		{
			return sctpChannel;
		}

		public int getNeighborId()
		{
			return neighborId;
		}
	}

	public Node(int rootId, int id, String neighbors, String nodeLocations)
	{
		FileHandler fh = null; 
		try {
			fh = new FileHandler("/home/005/r/rx/rxr151330/MyLogFile_"+id+".log");
			logger.addHandler(fh);
		} catch (SecurityException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		logger.info("rootId is"+rootId);
		logger.info("My id is"+rootId);
		logger.info("Neighbors is"+rootId);
		logger.info("Nodelocations is"+rootId);

		if(rootId == id)
		{
			explorePhase = true;
			lengthFromRoot = 0;
			FileWriter fos;
			try {
				fos = new FileWriter(new File(id+".txt"));
				BufferedWriter bw = new BufferedWriter(fos);
				bw.write("I'm root");
				bw.close();
			} catch (IOException e) {
				//e.printStackTrace();
			}
		}

		this.id = id;

		this.neighbors = new ArrayList<NodeNeighbor>();

		//processing node locations
		String[] locationsArr = nodeLocations.split("#");
		String[][] nodeDetails = new String[locationsArr.length][3];

		for(int i = 0; i < locationsArr.length; i++)
		{
			nodeDetails[i] = locationsArr[i].split(" ");
		}

		//process neighbors
		String[] neighborsArr = neighbors.split(" ");

		NodeNeighbor node;

		for(int i = 0; i < neighborsArr.length; i++)
		{
			int neighborId = Integer.parseInt(neighborsArr[i]);
			int neighborPort = Integer.parseInt(nodeDetails[neighborId][2]);
			try {
				node = new NodeNeighbor(neighborId, new InetSocketAddress(InetAddress.getByName(nodeDetails[neighborId][1]), neighborPort));
				this.neighbors.add(node);
			} catch (UnknownHostException e) {
				//e.printStackTrace();
			}
		}

		//getting my address
		int myPort = Integer.parseInt(nodeDetails[id][2]);
		try {
			this.address = new InetSocketAddress(InetAddress.getByName(nodeDetails[this.id][1]), myPort);
		} catch (UnknownHostException e) {
			logger.info("Unknown host exception");
			//e.printStackTrace();
		}


		Thread server = new Thread(new Runnable() {
			public void run()
			{
				logger.info("Start Server at node "+id);
				//start servers
				goServer();
			}
		});

		server.start();

		//start client and create channels
		boolean connected = false;
		try
		{
			Iterator<NodeNeighbor> it = this.neighbors.iterator();
			while(it.hasNext())
			{
				//Create a socket address for  server at net01 at port 5000
				NodeNeighbor neighbor = it.next();
				SocketAddress socketAddress = neighbor.getNeighborAddress();
				//Open a channel. NOT SERVER CHANNEL

				connected = false;
				SctpChannel sctpChannel = null;

				//waiting till server is up
				while(!connected)
				{
					sctpChannel = SctpChannel.open();
					//Bind the channel's socket to a local port. Again this is not a server bind
					sctpChannel.bind(new InetSocketAddress(neighbor.getNeighborAddress().getPort()));
					//Connect the channel's socket to  the remote server

					try
					{
						sctpChannel.connect(socketAddress);
						connected = true;
					}
					catch(ConnectException ce)
					{
						connected = false;
						try
						{
							logger.info("waiting for server at "+neighbor.getNeighborId()+" to come up"); 
							Thread.sleep(2000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}

				neighbor.setSctpChannel(sctpChannel);
			}
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}
	}

	private String createMessage(int nodeId, String type, int lengthFromRoot)
	{
		return String.valueOf(nodeId)+":"+type+":"+String.valueOf(lengthFromRoot);
	}

	private String getFromMessage(String message, int part)
	{
		String[] parts = message.split(":");
		return parts[part];
	}

	private String byteToString(ByteBuffer byteBuffer)
	{
		byteBuffer.position(0);
		byteBuffer.limit(MESSAGE_SIZE);
		byte[] bufArr = new byte[byteBuffer.remaining()];
		byteBuffer.get(bufArr);
		return new String(bufArr);
	}

	private void sendMessage(String message, SctpChannel channel) throws IOException
	{
		ByteBuffer byteBuffer = ByteBuffer.allocate(MESSAGE_SIZE);
		MessageInfo messageInfo = MessageInfo.createOutgoing(null,0);
		byteBuffer.clear();
		byteBuffer.put(message.getBytes());
		byteBuffer.flip();
		channel.send(byteBuffer,messageInfo);
	}

	class ServerIncomingReader implements Runnable
	{
		SctpChannel channel;
		ByteBuffer byteBuffer = ByteBuffer.allocate(MESSAGE_SIZE);
		String message;
		int destId = -1;
		boolean shutdownSent = false;
		boolean completedSent = false;

		public ServerIncomingReader(SctpChannel channel)
		{
			this.channel = channel;
		}

		@Override
		public void run()
		{
			MessageInfo messageInfo = null;

			while(true)
			{
				try
				{
					if(!oldParentList.isEmpty() && oldParentList.contains(destId))
					{
						//sending nack back
						try
						{
							logger.info("Sending nack back to "+destId);
							message = createMessage(id, "NACK", Integer.MIN_VALUE);
							sendMessage(message, channel);

							int index = 0;
							for(int oldParent : oldParentList)
							{
								if(oldParent == destId)
								{
									oldParentList.remove(index);
									break;
								}
								index++;
							}
						}
						catch (IOException e)
						{
							e.printStackTrace();
						}
					}

					logger.info("Node "+id+" Server waiting to receive explore messages");
					byteBuffer.clear();
					messageInfo = channel.receive(byteBuffer,null,null);
					logger.info("Message Info Node "+id+" "+byteToString(byteBuffer));

					if(messageInfo != null)
					{
						message = byteToString(byteBuffer);
					}

					if(destId == -1)
					{
						destId =  Integer.parseInt(getFromMessage(message, 0).trim());
						logger.info("Dest ID at node "+id+" is:"+destId);
					}

					//message format ID:EXPLORE
					synchronized (parentLock)
					{
						int recvLength = Integer.parseInt(getFromMessage(message, 2).trim());

						if((recvLength + 1) < lengthFromRoot)
						{
							if(!oldParentList.contains(parentId))
							{
								oldParentList.add(parentId);
							}

							parentId = destId;
							logger.info("Updated parent value to "+parentId);

							lengthFromRoot = recvLength + 1;
							logger.info("Updated lengthFromRoot value to "+lengthFromRoot);

							logger.info("Node "+id+" parent is "+parentId);
							FileWriter fos;
							try
							{
								fos = new FileWriter(new File("parent_"+id+".txt"));
								BufferedWriter bw = new BufferedWriter(fos);
								bw.write(String.valueOf(parentId));
								bw.close();
							}
							catch (IOException e)
							{
								e.printStackTrace();
							}

							//sending ack back
							try
							{
								logger.info("Sending ack back to "+destId);
								message = createMessage(id, "ACK", Integer.MIN_VALUE);
								sendMessage(message, channel);
							}
							catch (IOException e)
							{
								e.printStackTrace();
							}

							logger.info("Starting explore phase at node "+id);

							synchronized (sendLock)
							{
								explorePhase = true;
								sendLock.notify();
							}
						}
						else
						{
							//sending ack back
							try
							{
								logger.info("Sending reject back to "+destId+" since parent was not updated");
								message = createMessage(id, "REJECT", Integer.MIN_VALUE);
								sendMessage(message, channel);
							}
							catch (IOException e)
							{
								e.printStackTrace();
							}
						}
					}

					logger.info("Responded to client..Terminating server channel "+id+"--"+destId);
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
		}
	}

	private void goServer()
	{
		Thread t;
		SctpServerChannel sctpServerChannel = null;
		try
		{
			logger.info("Opening server at "+ address);
			//Open a server channel
			sctpServerChannel = SctpServerChannel.open();
			//Create a socket addess in the current machine at port 5000
			InetSocketAddress serverAddr = address;
			//Bind the channel's socket to the server in the current machine at port 5000
			logger.info(serverAddr.toString());
			sctpServerChannel.bind(serverAddr);
			//Server goes into a permanent loop accepting connections from clients			
			while(true)
			{
				SctpChannel channel = sctpServerChannel.accept();
				logger.info("Accepted new connection");
				t = new Thread(new ServerIncomingReader(channel));
				t.start();
			}
		}
		catch(IOException ioe)
		{
			ioe.printStackTrace();
		}
	}

	class IncomingReader implements Runnable
	{
		SctpChannel sctpChannel;
		public static final int MESSAGE_SIZE = 100;
		ByteBuffer byteBuffer = ByteBuffer.allocate(MESSAGE_SIZE);
		String message;
		int senderId = -1;

		public IncomingReader(SctpChannel sctpChannel)
		{
			this.sctpChannel = sctpChannel;
		}

		@Override
		public void run() 
		{
			MessageInfo messageInfo = null;
			while(true)
			{
				messageInfo = null;

				synchronized (recvLock)
				{
					try
					{
						recvLock.wait();
					}
					catch (InterruptedException e)
					{
						e.printStackTrace();
					}
				}

				try
				{
					logger.info("Receive thread waiting to receive");
					byteBuffer.clear();
					messageInfo = sctpChannel.receive(byteBuffer,null,null);
					logger.info("Node client "+id+" received acknowledgement message "+byteToString(byteBuffer)+" remote address "+sctpChannel.getRemoteAddresses());
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}

				if(messageInfo != null)
				{
					message = byteToString(byteBuffer);
				}

				if(senderId == -1)
					senderId = Integer.parseInt(getFromMessage(message, 0).trim());

				String type = getFromMessage(message, 1);
				if(type.equalsIgnoreCase("ACK") || type.equalsIgnoreCase("NACK"))
				{
					if(type.equalsIgnoreCase("ACK"))
					{
						logger.info("Received ack from "+ senderId);
						logger.info("ack msg:"+message);
						if(!childrenList.contains(senderId))
						{
							childrenList.add(senderId);
						}
					}
					else if(type.equalsIgnoreCase("NACK"))
					{
						logger.info("Received nack from "+ senderId);
						logger.info("nack msg:"+message);
						int index = 0;
						for(int child : childrenList)
						{
							if(child == senderId)
							{
								childrenList.remove(index);
								break;
							}
							index++;
						}
					}

					FileWriter fos;
					try
					{
						fos = new FileWriter(new File("children_"+id+".txt"));
						BufferedWriter bw = new BufferedWriter(fos);
						bw.write(childrenList.toString());
						bw.close();
					}
					catch (IOException e)
					{
						e.printStackTrace();
					}
				}
			}
		}
	}

	public void receiveMessages()
	{
		Iterator<NodeNeighbor> it = neighbors.iterator();
		Thread t = null;

		while(it.hasNext())
		{
			t = new Thread(new IncomingReader(it.next().getSctpChannel()));
			t.start();
		}
	}

	public void sendMessageToNeighbors()
	{
		ByteBuffer byteBuffer = ByteBuffer.allocate(MESSAGE_SIZE);
		String message = null;

		while(true)
		{
			if(!explorePhase)
			{
				synchronized (sendLock)
				{
					logger.info("Waiting the client send thread since its not explore phase yet");
					try
					{
						sendLock.wait();
					}
					catch (InterruptedException e)
					{
						e.printStackTrace();
					}

					logger.info("Client send Thread notified");
					continue;
				}
			}

			message = createMessage(id, "EXPLORE", lengthFromRoot);

			Iterator<NodeNeighbor> it = this.neighbors.iterator();

			synchronized (sendLock)
			{
				while(it.hasNext())
				{
					NodeNeighbor neighbor = it.next();
					if(neighbor.getNeighborId() != parentId)
					{
						MessageInfo messageInfo = MessageInfo.createOutgoing(null,0);
						byteBuffer.clear();

						byteBuffer.put(message.getBytes());
						logger.info("Message to be sent from buffer is" + byteToString(byteBuffer));					

						byteBuffer.flip();

						try
						{
							logger.info("Node "+id+" sending message "+message+" to "+"node "+neighbor.getNeighborId()+" @ "+neighbor.getSctpChannel().getRemoteAddresses());
							neighbor.getSctpChannel().send(byteBuffer,messageInfo);
						}
						catch (IOException e)
						{
							e.printStackTrace();
						}	
					}
				}

				explorePhase = false;
				logger.info("All message sent out to neighbors");

				logger.info("Notifying receivers");

				synchronized (recvLock)
				{
					recvLock.notifyAll();
				}
			}
		}
	}


	public void goClient()
	{
		receiveMessages();

		Thread clientSendThread = new Thread(new Runnable() {
			@Override
			public void run() {
				sendMessageToNeighbors();
			}
		});

		clientSendThread.start();

		try {
			clientSendThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args)
	{
		Node node = new Node(Integer.parseInt(args[1]), Integer.parseInt(args[0]), args[3], args[2]);
		node.goClient();
	}
}
