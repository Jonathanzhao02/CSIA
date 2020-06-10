import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import java.io.IOException;

import javafx.collections.ObservableList;

/**
 * This is the class that handles the DatagramSocket for server discovery and client connection validation (heartbeat)
 * 
 * @author Jonathan Zhao
 * @version 1.0
 */
public class DiscoveryHandler extends Thread {
    private DatagramSocket socket;
    private int port;

    private static ObservableList<ClientHandler> clientList;
    private static final String requestString = "LH_DISCOVER_REQUEST";
    private static final String responseString = "LH_DISCOVER_RESPONSE";
    private static final String checkString = "LH_CHECK_CONNECTION";
    private static final String connectedString = "LH_CONNECTED";

    /**
     * Sets the client list
     * 
     * @param clientList The ObservableList of ClientHandlers to be set as the client list
     */
    public static void setClientList(ObservableList<ClientHandler> clientList){
        DiscoveryHandler.clientList = clientList;
    }

    /**
     * Constructor for the DiscoveryHandler class
     * 
     * @param port The port the Server is using
     */
    public DiscoveryHandler(int port){
        this.port = port;
    }

    /**
     * Constantly parses through packets received
     */
    @Override
    public void run(){

        try{
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);

            while(true){
                byte[] recvBuf = new byte[800];
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(packet);
                String message = new String(packet.getData()).trim();

                if(message.equals(requestString)){
                    System.out.println("Packet data: " + message);
                    byte[] sendData = responseString.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, packet.getAddress(), packet.getPort());
                    socket.send(sendPacket);
                    System.out.println("Discovery sent packet " + new String(sendPacket.getData()) + " to " + packet.getSocketAddress());
                } else if(message.equals(checkString)){
                    byte[] sendData = connectedString.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, packet.getAddress(), packet.getPort());
                    socket.send(sendPacket);
                    validateConnection(packet.getAddress());
                } else{
                    System.out.println("Mysterious packet " + message + " from " + packet.getAddress());
                }

            }

        } catch(IOException e){
            e.printStackTrace();
        }

    }

    /**
     * Resets the timer of a certain client
     * 
     * @param address The InetAddress of the client who sent a heartbeat packet
     */
    private void validateConnection(InetAddress address){
        ClientHandler currentClient;

        for(int i = clientList.size() - 1; i >= 0; i--){
            currentClient = clientList.get(i);

            if(currentClient != null && currentClient.getAddress().equals(address)){
                currentClient.resetTimer();
                return;
            }

        }

    }

}