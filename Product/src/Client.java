import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Enumeration;
import java.util.concurrent.LinkedBlockingQueue;

import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.AWTException;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.IIOImage;
import javax.imageio.stream.ImageOutputStream;

import javafx.application.Application;
import javafx.application.Platform;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Alert.AlertType;

import javafx.animation.Timeline;
import javafx.animation.KeyFrame;

import javafx.stage.Stage;

import javafx.util.Duration;

/**
 * This is the main class for the client which handles all necessary GUI and network logic
 * 
 * @author Jonathan Zhao
 * @version 1.0
 */
public class Client extends Application{
    private static Socket clientSocket;
    private static OutputStream out = null;
    private static InputStream in = null;
    private static LinkedBlockingQueue<Runnable> connectionRequests = new LinkedBlockingQueue<Runnable>();
    private static LinkedBlockingQueue<Runnable> readRequests = new LinkedBlockingQueue<Runnable>();
    private static volatile Boolean streaming = false;
    private static volatile Boolean connected = false;
    private static volatile InetAddress serverIp = null;

    private static Rectangle screenRect = null;
    private static Robot robot = null;
    private static ImageWriter jpgWriter = null;
    private static ImageWriteParam jpgWriteParam = null;

    private static DatagramSocket discoverySocket;
    private static byte[] discoverRecvBuf;
    private static DatagramPacket discoverReceivePacket;
    private static String discoverMessage;

    private static final String requestString = "LH_DISCOVER_REQUEST";
    private static final String responseString = "LH_DISCOVER_RESPONSE";
    private static final String checkString = "LH_CHECK_CONNECTION";
    private static final String connectedString = "LH_CONNECTED";
    private static final String startString = "LH_START";
    private static final String stopString = "LH_STOP";
    private static final String msgString = "LH_SENDMSG";
    private static final int checkDelay = 500;
    private static final int timeoutDelay = 10000;
    private static final int port = 53;
    private static final float compressionQuality = 0.8f;

    private static Alert alert = new Alert(AlertType.NONE, "", ButtonType.OK);
    private static String alertMessage;

    /**
     * Shows an alert on the JavaFX application thread
     */
    private static Timeline alertTimeline =
    new Timeline(new KeyFrame(Duration.millis(1), e -> {
        System.out.println("Sending message");
        alert.setContentText(alertMessage);
        alert.show();
    }));

    /**
     * Constantly tries to maintain a connection with the server
     */
    private static Thread discoveryThread =
    new Thread(() -> {
        
        try{
            discoverySocket = new DatagramSocket();
            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(timeoutDelay);
            discover();
        } catch(IOException ioE){
            System.out.println("Failed to create datagram socket");
        }

    });

    /**
     * Constantly executes any runnables in the readRequests queue
     */
    private static Thread readThread = 
    new Thread(() -> {

        while(true){
            
            try{
                readRequests.take().run();
            } catch(InterruptedException e){
                e.printStackTrace();
            }

        }

    });

    /**
     * Constantly executes any runnables in the connectionRequests queue
     */
    private static Thread connectionThread =
    new Thread(() -> {

        while(true){

            try{
                connectionRequests.take().run();
            } catch(InterruptedException e){
                e.printStackTrace();
            }

        }

    });

    /**
     * Sends packets to all open addresses on the device's network
     * 
     * @throws SocketException Throws a SocketException when a packet fails to send
     */
    private static void sendDiscoveryPackets() throws SocketException{
        byte[] sendData = requestString.getBytes();
        Enumeration interfaces = NetworkInterface.getNetworkInterfaces();

        while(interfaces.hasMoreElements()){
            NetworkInterface networkInterface = (NetworkInterface) interfaces.nextElement();

            if(networkInterface.isLoopback()){
                continue;
            }

            for(InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()){
                InetAddress broadcast = interfaceAddress.getBroadcast();

                if(broadcast == null){
                    continue;
                }

                try{
                    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, broadcast, port);
                    discoverySocket.send(sendPacket);
                    System.out.println("Sent packet to " + broadcast.getHostAddress() + "; Interface: " + networkInterface.getDisplayName());
                } catch(Exception e){
                    System.out.println("Could not send packet to " + broadcast.getHostAddress());
                }

            }

        }

    }

    /**
     * Receives and parses a single packet
     * 
     * @throws SocketException Throws a SocketException when the DatagramSocket fails
     */
    private static void receivePacket() throws SocketException{
        discoverRecvBuf = new byte[15000];
        discoverReceivePacket = new DatagramPacket(discoverRecvBuf, discoverRecvBuf.length);

        try{
            discoverySocket.receive(discoverReceivePacket);
            System.out.println("Received response from server: " + discoverReceivePacket.getAddress().getHostAddress());
            discoverMessage = new String(discoverReceivePacket.getData()).trim();

            if(discoverMessage.equals(responseString)){
                System.out.println("Found server!");
                serverIp = discoverReceivePacket.getAddress();
                connected = true;
            } else{
                System.out.println("Received: " + discoverMessage);
            }

        } catch(SocketTimeoutException sE){
            System.out.println("Reply not received, server undiscovered");
        } catch(IOException IOe){
            System.out.println("Failed to receive packet");
        }

    }

    /**
     * Recursive method that constantly attempts to discover and check the connection to the server
     */
    private static void discover(){

        try{
            sendDiscoveryPackets();
            System.out.println("Now waiting for reply");
            receivePacket();

            if(connected){
                connectionRequests.add(() -> {
                    attemptConnection();
                });

                while(connected){
                    connected = checkConnection(discoverReceivePacket.getAddress());
                    Thread.sleep(checkDelay);
                }

            }

            System.out.println("Restarting discovery");
            discover();
        } catch(SocketException e){
            System.out.println("DatagramSocket failed");
            e.printStackTrace();
        } catch(InterruptedException Ie){
            System.out.println("Packet thread interrupted");
        }

    }

    /**
     * Checks the connection by sending and then receiving a packet
     * 
     * @param address The InetAddress to which the packet should be sent
     * @return Returns whether the correct response was received from the server in time
     */
    private static Boolean checkConnection(InetAddress address){

        try{
            byte[] sendMsg = checkString.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendMsg, sendMsg.length, address, port);
            discoverySocket.send(sendPacket);

            byte[] recvBuf = new byte[15000];
            DatagramPacket receivePacket = new DatagramPacket(recvBuf, recvBuf.length);
            discoverySocket.receive(receivePacket);
            
            String message = new String(receivePacket.getData()).trim();

            if(message.equals(connectedString)){
                return true;
            } else{
                return false;
            }
        
        } catch(SocketTimeoutException e){
            System.out.println("Reply not received, disconnected");
            return false;
        } catch(IOException e){
            e.printStackTrace();
            return false;
        }

    }
 
    /**
     * Reads and parses the next message from the connected Socket's InputStream
     */
    private static void readFromConnection(){
        byte[] outputData;
        String output;

        while(connected){

            outputData = new byte[1024];

            try{
                in.read(outputData);
            } catch(IOException ioE){
                System.out.println("Could not read from input");
            }

            output = new String(outputData).trim();

            if(output.length() > 0){
                System.out.println(output);

                if(output.equals(startString)){
                    streaming = true;
                } else if(output.equals(stopString)){
                    streaming = false;
                } else if(output.contains(msgString)){
                    showAlert(output.substring(msgString.length(), output.length()));
                }

            }

        }

    }

    /**
     * Starts all threads, sets up screen streaming, and calls the application's launch method
     */
    public static void main(String[] args) {
        screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

        try{
            robot = new Robot();
        } catch(AWTException awtE){
            throw new RuntimeException("Failed to create robot, likely insufficient permissions");
        }

        discoveryThread.start();
        readThread.start();
        connectionThread.start();

        alertTimeline.setOnFinished(e -> {
            alertTimeline.stop();
        });

        alert.setTitle("Teacher Message");
        Platform.setImplicitExit(false);
        launch(args);
    }

    /**
     * Waits until a server address is found and then starts the connection
     */
    private static void attemptConnection(){

        try{

            while(serverIp == null){
                Thread.sleep(500);
            }

            startConnection(serverIp);
        } catch(InterruptedException iE){
            System.out.println("Connection thread interrupted");
        }

    }

    /**
     * Resets all necessary variables and cleans up the I/O streams and Sockets
     */
    private static void stopClient(){

        try{
            streaming = false;
            connected = false;
            serverIp = null;
            out.close();
            in.close();
            clientSocket.close();
        } catch(SocketException sE){
            System.out.println("Pipeline broken");
        } catch(IOException ioE){
            System.out.println("Failed to close streams");
        }

    }

    /**
     * Writes the length of the message to the OutputStream
     * 
     * @param output The OutputStream to be written to
     * @param length The length of the message
     * @throws IOException Throws an IOException when the OutputStream cannot be written to
     */
    private static void writeLength(OutputStream output, int length) throws IOException{
        byte[] sendBytes = new byte[32];
        byte[] rawBytes = Integer.toString(length).getBytes();

        for(int i = 0; i < rawBytes.length; i++){
            sendBytes[i] = rawBytes[i];
        }

        out.write(sendBytes);
    }

    /**
     * Starts connection with server by opening a Socket, creating all I/O streams, sending the username, and finally sending the screen
     * 
     * @param servIp The InetAddress of the server
     */
    private static void startConnection(InetAddress servIp){
        
        try {
            System.out.println("Connecting...");
            clientSocket = new Socket(servIp, port);
            System.out.println("Connected to " + clientSocket.getInetAddress());
            connected = true;
            out = clientSocket.getOutputStream();
            in = clientSocket.getInputStream();
            jpgWriter = ImageIO.getImageWritersByFormatName("jpg").next();
            jpgWriteParam = jpgWriter.getDefaultWriteParam();
            jpgWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            jpgWriteParam.setCompressionQuality(compressionQuality);
            byte[] username = System.getProperty("user.name").getBytes();
            writeLength(out, username.length);
            out.write(username);

            readRequests.add(() -> {
                readFromConnection();
            });

            while(connected){

                if(streaming){
                    sendScreen();
                }

                Thread.sleep(100);
            }

        } catch(SocketException sE){
            System.out.println("Server disconnected");
        } catch(IOException ioE){
            System.out.println("Interrupted streaming operation");
        } catch(InterruptedException iE){
            System.out.println("Thread interrupted");
        } finally{
            jpgWriter.dispose();
            stopClient();
            System.out.println("Stopped connection");
        }
        
    }

    /**
     * Sends the screen as a byte array to the server
     * 
     * @throws IOException Throws an IOEXception when it fails to write to the connected Socket's OutputStream
     */
    private static void sendScreen() throws IOException{
        BufferedImage capture;
        capture = robot.createScreenCapture(screenRect);
        ByteArrayOutputStream imgOutput = new ByteArrayOutputStream();
        ImageOutputStream imgOutputStream = ImageIO.createImageOutputStream(imgOutput);
        jpgWriter.setOutput(imgOutputStream);
        jpgWriter.write(null, new IIOImage(capture, null, null), jpgWriteParam);
        byte[] imgData = imgOutput.toByteArray();
        imgOutput.close();
        imgOutputStream.close();
        writeLength(out, imgData.length);
        out.write(imgData);
    }

    /**
     * Displays the alert on the Client's screen
     * 
     * @param message The String to be displayed on the Client's screen
     */
    private static void showAlert(String message){
        alertMessage = message;
        alertTimeline.play();
    }

    /**
     * Unused method, necessary to extend the JavaFX application
     * 
     * @param mainStage Unused argument
     */
    @Override
    public void start(Stage mainStage){
        
    }

    /**
     * On shutdown, interrupts all threads and cleans up the Client
     */
    @Override
    public void stop(){
        readThread.interrupt();
        connectionThread.interrupt();
        discoveryThread.interrupt();
        stopClient();
    }

}