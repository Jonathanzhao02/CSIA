import java.net.ServerSocket;
import java.net.Socket;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javafx.stage.Stage;
import javafx.scene.Scene;

import javafx.application.Application;
import javafx.application.Platform;

import javafx.scene.image.ImageView;
import javafx.scene.image.Image;

import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.cell.PropertyValueFactory;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * This is the main class for the server which handles the GUI, client connections, and discovery
 * 
 * @author Jonathan Zhao
 * @version 1.0
 */
public class Server extends Application {
	private int maxClients = 100;
	private int port = 53;
	private Boolean streaming = false;
	private ClientHandler activeClient = null;
	private ServerSocket serverSocket = null;
	private Socket clientSocket = null;
	private DiscoveryHandler discoveryHandler = null;

	private Stage mainStage = null;
	private ImageView streamView = new ImageView();
	private BorderPane rootNode = new BorderPane();
	private Button streamControlBtn = new Button("START");
	private Button sendMsgBtn = new Button("SEND");
	private TextField messageField = new TextField();

	private ObservableList<ClientHandler> clientList = FXCollections.observableArrayList();
	private TableView<ClientHandler> UIclients = new TableView<ClientHandler>(clientList);
	private TableColumn<ClientHandler, String> UIconnected = new TableColumn<ClientHandler, String>("Connected Computers");

	private VBox menu = new VBox(streamControlBtn, UIclients);
	private HBox msgBox = new HBox(sendMsgBtn, messageField);

	private static final int menuWidth = 200;
	private static final int msgBoxHeight = 30;

	private Alert errorAlert = new Alert(Alert.AlertType.ERROR);
	private Alert infoAlert = new Alert(Alert.AlertType.INFORMATION);

	/**
	 * Constantly accepts and processes new clients
	 */
	private Thread acceptThread = new Thread(() -> {

		try{

			while(true){
				clientSocket = serverSocket.accept();
				tryAdd(clientSocket);
			}

		} catch(IOException e){
			showError("Server suddenly stopped");
		}

	});
	
	/**
	 * Shows an error message in the GUI
	 * 
	 * @param message The error message to be shown
	 */
	private void showError(String message){

		Platform.runLater(() -> {
			errorAlert.setTitle("Error");
			errorAlert.setContentText(message);
			errorAlert.showAndWait();
		});
		
	}

	/**
	 * Shows an informative message in the GUI
	 * 
	 * @param message The message to be shown
	 */
	private void showInfo(String message){

		Platform.runLater(() -> {
			infoAlert.setTitle("Message");
			infoAlert.setContentText(message);
			infoAlert.showAndWait();
		});

	}

	/**
	 * Calls the application launch method
	 * 
	 * @param args Command line arguments are unused
	 */
	public static void main(String[] args){
		launch(args);
	}

	/**
	 * Creates the scene and starts the server
	 * 
	 * @param mainStage The Stage the GUI is showed on
	 */
	public void start(Stage mainStage){
		this.mainStage = mainStage;
		mainStage.setOnCloseRequest(e -> {

			try{
				showInfo("Server stopping");
				shutdownClients();
				serverSocket.close();
				discoveryHandler.interrupt();
				acceptThread.interrupt();
				Platform.exit();
				System.exit(0);
			} catch(Exception ex){
				ex.printStackTrace();
			}

		});

		createScene();

		try{
			startServer();
		} catch(IOException e){
			showError("Server could not be created");
		}

	}

	/**
	 * Requests a ClientHandler to stream, disabling all others
	 * 
	 * @param chosenClient The ClientHandler that should be streamed
	 */
	private void stream(ClientHandler chosenClient) throws IOException {

		for(ClientHandler client : clientList){

			if(client != chosenClient){
				client.stopStreaming();
			} else if(client == chosenClient){
				client.startStreaming();
				changeText(streamControlBtn, "STOP");
				streaming = true;
			} else{
				throw new RuntimeException("This should NEVER happen.\nClient is null.");
			}

		}

	}

	/**
	 * Changes a Button's text later in the application thread.
	 * Necessary to avoid concurrent modification of text
	 * 
	 * @param t The Button whose text should be modified
	 * @param msg The String that should replace the current text of the Button
	 */
	private void changeText(Button t, String msg){

		Platform.runLater(() -> {
			t.setText(msg);
		});

	}

	/**
	 * Changes a TextField's text later in the application thread.
	 * Necessary to avoid concurrent modification of text
	 * 
	 * @param t The TextField whose text should be modified
	 * @param msg The String that should replace the current text of the TextField
	 */
	private void changeText(TextField t, String msg){

		Platform.runLater(() -> {
			t.setText(msg);
		});

	}

	/**
	 * Called upon clicking the 'Send' button or pressing the Enter key in the TextField.
	 * Handles necessary logic to send a message to the selected client
	 */
	private void sendMessageText(){
		
		try{

			if(messageField.getText().length() < 200){

				if(messageField.getText().length() > 0){
					
					if(activeClient != null){
						activeClient.sendAlert(messageField.getText());
						changeText(messageField, "");
					} else{
						showError("No active client selected!");
					}

				} else{
					showError("No message!");
				}

			} else{
				showInfo("Please keep messages under 200 characters");
			}
			
		} catch(IOException ioE){
			showError("Failed to send message");
			ioE.printStackTrace();
		}

	}

	/**
	 * Handles the creation of the layout and logic of the GUI
	 */
	private void createScene(){
		rootNode.setCenter(streamView);
		rootNode.setLeft(menu);
		rootNode.setBottom(msgBox);
		UIconnected.setCellValueFactory(new PropertyValueFactory<ClientHandler, String>("username"));
		UIconnected.prefWidthProperty().bind(UIclients.prefWidthProperty());
		UIclients.getColumns().add(UIconnected);
		UIclients.setPrefWidth(menuWidth);
		UIclients.prefHeightProperty().bind(mainStage.heightProperty().subtract(sendMsgBtn.heightProperty()).subtract(streamControlBtn.heightProperty()));
		UIclients.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
		UIclients.getSelectionModel().setCellSelectionEnabled(true);
		UIclients.getSelectionModel().selectedItemProperty().addListener((obs, ol, ne) -> {

			if(ne != null){
				activeClient = ne;

				try{
					stream(ne);
				} catch(IOException ioE){
					showError("Could not start streaming");
				}


			}

		});

		streamControlBtn.setPrefWidth(menuWidth);
		streamControlBtn.setOnAction(e -> {

			try{

				if(activeClient != null){

					if(!streaming){
						activeClient.startStreaming();
						changeText(streamControlBtn, "STOP");
					} else{
						activeClient.stopStreaming();
						changeText(streamControlBtn, "START");
						clearImage();
					}

					streaming = !streaming;
				} else{
					showError("No active client selected!");
				}

			} catch(IOException ioE){
				showError("Client disconnected");
				removeClient(activeClient);
				activeClient = null;
				streaming = false;
				changeText(streamControlBtn, "START");
				clearImage();
			}

		});

		sendMsgBtn.setOnAction(e -> {
			sendMessageText();
		});

		messageField.prefWidthProperty().bind(mainStage.widthProperty().subtract(sendMsgBtn.widthProperty()));
		messageField.setOnAction(e -> {
			sendMessageText();
		});

		streamView.setPreserveRatio(true);
		streamView.fitWidthProperty().bind(mainStage.widthProperty().subtract(menuWidth));
		streamView.fitHeightProperty().bind(mainStage.heightProperty().subtract(msgBoxHeight));
		mainStage.setScene(new Scene(rootNode, 1200, 600));
		mainStage.show();
	}

	/**
	 * Sets the image of the Server ImageView
	 * 
	 * @param bytes The byte array to be converted into an image
	 */
	public void setImage(byte[] bytes){
		streamView.setImage(new Image(new ByteArrayInputStream(bytes)));
	}

	/**
	 * Recreates the Server ImageView to clear it
	 */
	public void clearImage(){

		Platform.runLater(() -> {
			rootNode.getChildren().remove(streamView);
			streamView = new ImageView();
			streamView.setPreserveRatio(true);
			streamView.fitWidthProperty().bind(mainStage.widthProperty().subtract(menuWidth));
			streamView.fitHeightProperty().bind(mainStage.heightProperty().subtract(msgBoxHeight));
			rootNode.setCenter(streamView);
			streaming = false;
		});
		
	}

	/**
	 * Starts up all the network logic of the Server
	 * 
	 * @throws IOException Throws an IOException whenever the Server fails to start up
	 */
	private void startServer() throws IOException {
		serverSocket = new ServerSocket(port);
		discoveryHandler = new DiscoveryHandler(port);
		discoveryHandler.start();
		DiscoveryHandler.setClientList(clientList);
		showInfo("Server started");
		acceptThread.start();
	}

	/**
	 * Removes a ClientHandler from the active client list
	 * 
	 * @param client The ClientHandler to be removed from the client list
	 */
	public void removeClient(ClientHandler client){
		client.stopConnection();
		clientList.remove(client);
	}

	/**
	 * Attempts to create a new ClientHandler and add it to the active user list.
	 * Fails when the maximum number of users has been reached
	 * 
	 * @param s The Socket that is connected to the new client
	 */
	private synchronized void tryAdd(Socket s){

		try{

			if(clientList.size() < maxClients){
				clientList.add(new ClientHandler(s, this));
			} else{
				s.close();
			}

		} catch(IOException ioE){
			ioE.printStackTrace();
		}

	}

	/**
	 * Shuts down every ClientHandler in the active client list
	 */
	private void shutdownClients(){

		for(ClientHandler client : clientList){

			if(client != null){
				client.stopConnection();
			}

		}

	}
	
}