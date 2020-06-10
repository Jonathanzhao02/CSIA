import java.net.Socket;
import java.net.InetAddress;
import java.net.SocketException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Date;

import javafx.beans.property.SimpleStringProperty;

/**
 * This is the class that handles connections and communication to clients
 * 
 * @author Jonathan Zhao
 * @version 1.0
 */
public class ClientHandler extends Thread {
    private InetAddress address;
    private Date date;
    private SimpleStringProperty username;

    private Socket clientSocket;
	private OutputStream out;
	private InputStream in;
	private Server server;
	private Boolean streaming = false;
	private volatile Boolean connected = true;

	private static final String startString = "LH_START";
	private static final String stopString = "LH_STOP";
	private static final String msgString = "LH_SENDMSG";
	private static final int kickoutDelay = 10000;

	/**
	 * Constantly checks the connection with the client
	 */
	private Thread checkThread = new Thread(() -> {
		
		try{
			Thread.sleep(1000);

			while(!clientSocket.isClosed()){
				checkConnection();
				Thread.sleep(100);
			}
		
		} catch(InterruptedException interruptEx){
			System.out.println("Check thread interrupted");
		}

	});

	/**
	 * Constructor for the ClientHandler object
	 * 
	 * @param socket The Socket connected to the client
	 * @param server The Server to which the client is connected
	 */
	public ClientHandler(Socket socket, Server server){
		this.server = server;
        clientSocket = socket;
        date = new Date();
        address = socket.getInetAddress();
        username = new SimpleStringProperty(address.toString());
        this.start();
	}
	
	/**
	 * Cleans up all Sockets, I/O streams, and Threads
	 */
	public void stopConnection(){

		try{
            clientSocket.close();
			in.close();
			out.close();
			checkThread.interrupt();
			this.interrupt();
		} catch(IOException ioE){
			System.out.println("Could not close I/O");
		} catch(Exception e){
			e.printStackTrace();
		}
		
	}

	/**
	 * Checks the time between the previous heartbeat from the client and the moment the method is called
	 */
	private void checkConnection(){
		long delay = new Date().getTime() - date.getTime();

		if(delay > kickoutDelay + 1000){
			System.out.println("Client " + address + " has disconnected with delay of " + delay);
			System.out.println("Disconnecting with " + clientSocket.getRemoteSocketAddress().toString());
		
			try{
				connected = false;
			} catch(Exception E){
				E.printStackTrace();
			}

			System.out.println("Disconnected");

			if(streaming){
				server.clearImage();
			}

			server.removeClient(this);
		}

	}

	/**
	 * Reads an exact number of bytes from a stream
	 * 
	 * @param input The InputStream to be read from
	 * @param size The number of bytes to be read
	 * @return Returns the bytes read
	 * @throws IOException Throws an IOException whenever the InputStream cannot be read from
	 */
	private byte[] readExactly(InputStream input, int size) throws IOException{
		byte[] data = new byte[size];
		int readSize = input.read(data, 0, size);

		while(readSize < size && readSize != -1){
			readSize += input.read(data, readSize, size - readSize);
		}

		return data;
	}

	/**
	 * Reads the name of the connected client
	 * 
	 * @throws IOException Throws an IOException whenver the connected Socket's InputStream cannot be read from
	 */
	private void readName() throws IOException{
		byte[] recvBuf = new byte[32];
		String parsed = new String();
		
		while(parsed.length() == 0 && clientSocket.isConnected() && !clientSocket.isClosed()){
			in.read(recvBuf);
			int length = Integer.parseInt(new String(recvBuf).trim());
			recvBuf = readExactly(in, length);
			parsed = new String(recvBuf).trim();
		}

		username.set(parsed);
	}

	/**
	 * Sets up client and starts all necessary logic
	 */
	@Override
	public void run(){
		System.out.println("Connected with " + clientSocket.getRemoteSocketAddress().toString());
		
		try{
			out = clientSocket.getOutputStream();
			in = clientSocket.getInputStream();
		} catch(IOException ioE){
			System.out.println("Could not initialize client I/O");
			return;
		}

		System.out.println("Client now speaking");
		checkThread.start();
		
		try{
			readName();
		} catch(IOException ioE){
			System.out.println("Could not read name");
			return;
		}
		
		while(connected){
			readInputStream();
		}

    }

	/**
	 * Reads the length of the subsequent message.
	 * Length is assumed to be readable with a 32 byte array
	 * 
	 * @return Returns the length of the message
	 * @throws IOException Throws an IOException whenever the connected Socket's InputStream cannot be read from
	 */
    private int readMessageLength() throws IOException{
        byte[] recvBuf = new byte[32];
        in.read(recvBuf);
        String parsed = new String(recvBuf).trim();
        int length = -1;

        try{

            if(parsed.length() > 0){
                length = Integer.parseInt(parsed);
            }

        } catch(NumberFormatException nfeEx){
            length = -1;
        }
        
        return length;
    }
	
	/**
	 * Attempts to read from the input stream
	 */
    private void readInputStream(){

        try{
			int imgSize = readMessageLength();
			byte[] imgData = readExactly(in, imgSize);

            if(imgSize != -1 && streaming){
                server.setImage(imgData);
            }

        } catch(SocketException sE){
            System.out.println("Pipeline broken");
            connected = false;
        } catch(Exception ex){
            ex.printStackTrace();
            connected = false;
        }

    }

	/**
	 * Sends streaming request to the client to start (true) or stop (false)
	 * 
	 * @param b Indicates whether the client should stream or not
	 * @throws IOException Throws an IOException when the command cannot be sent over the connected Socket's OutputStream
	 */
    private void requestStreaming(Boolean b) throws IOException{

		if(!streaming){
			out.write(startString.getBytes());
		} else{
			out.write(stopString.getBytes());
		}

		streaming = b;
	}

	/**
	 * Sends a message to be displayed on the client's screen
	 * 
	 * @param message The String to be displayed
	 * @throws IOException Throws an IOException if the message cannot be send over the connected Socket's OutputStream
	 */
	public void sendAlert(String message) throws IOException{
		out.write((msgString + message).getBytes());
    }
	
	/**
	 * Resets the timer used to indicate the time between heartbeats from the client
	 */
    public void resetTimer(){
        date = new Date();
    }

	/**
	 * @return Returns the username SimpleStringProperty
	 */
    public SimpleStringProperty usernameProperty(){return username;}

	/**
	 * @return Returns the client's InetAddress
	 */
    public InetAddress getAddress(){return address;}

	/**
	 * @return Returns if the socket is connected or not
	 */
    public Boolean isConnected(){return connected;}

	/**
	 * Requests the client to start streaming
	 * 
	 * @throws IOException Throws an IOException when the command cannot be sent
	 */
    public void startStreaming() throws IOException{requestStreaming(true);}

	/**
	 * Requests the client to stop streaming
	 * 
	 * @throws IOException Throws an IOException when the command cannot be sent
	 */
    public void stopStreaming() throws IOException{requestStreaming(false);}

}