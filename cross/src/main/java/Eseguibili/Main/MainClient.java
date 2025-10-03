package Eseguibili.Main;

//Import delle librerie
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;

import Eseguibili.Client.Printer;
import Eseguibili.Client.ReceiverClient;
import Eseguibili.Client.UDP;
import Gson.GsonAskHistory;
import Gson.GsonCredentials;
import Gson.GsonLimitStopOrder;
import Gson.GsonMarketOrder;
import Gson.GsonMessage;
import Gson.GsonResponseOrder;
import Gson.GsonUser;
import Gson.Values;

// Classe principale del client
public class MainClient {
    // Configurazione del client
    public static final String configFile = "client.properties";
    public static String hostname;          //Hostname del server
    public static int TCPport;              //Porta TCP del server

    // Socket e stream
    private static Socket TCPsocket;
    private static PrintWriter out;                 //Stream di output TCP
    private static BufferedReader in;               //Stream di input TCP    
    private static Thread UDPreceiver = null;       //Thread di ricezione UDP
    private static Thread receiver = null;          //Thread di ricezione TCP
    private static Scanner scanner = new Scanner(System.in);        //Scanner per input utente
    private static Gson gson = new Gson();
    private static GsonMessage<Values> mes;

    // Flags
    private static boolean udpMessage = false;

    // Comandi validi
    private static final String[] validCommands = {
        "register\\s*\\(\\s*[a-zA-Z0-9]+\\s*,\\s*\\S(?:.*\\S)?\\s*\\)",
        "updateCredentials\\s*\\(\\s*[a-zA-Z0-9]+\\s*,\\s*\\S(?:.*\\S)?\\s*,\\s*\\S(?:.*\\S)?\\s*\\)",
        "login\\s*\\(\\s*[a-zA-Z0-9]+\\s*,\\s*\\S(?:.*\\S)?\\s*\\)",
        "logout\\s*\\(\\s*\\)",
        "insertMarketOrder\\s*\\(\\s*[a-zA-Z]+\\s*,\\s*\\d+\\s*\\)",
        "insertLimitOrder\\s*\\(\\s*[a-zA-Z]+\\s*,\\s*\\d+\\s*,\\s*\\d+(\\.\\d+)?\\s*\\)",
        "insertStopOrder\\s*\\(\\s*[a-zA-Z]+\\s*,\\s*\\d+\\s*,\\s*\\d+(\\.\\d+)?\\s*\\)",
        "cancelOrder\\s*\\(\\s*\\d+\\s*\\)",
        "getPriceHistory\\s*\\(\\s*\\d+\\s*\\)",
        "showOrderBook\\s*\\(\\s*\\)",
        "showStopOrders\\s*\\(\\s*\\)",
        "help"
    };

    // Messaggio di aiuto
    private static String helpMessage = "- register(username, password)\n" +
            "- updateCredentials(username, currentPassword, newPassword)\n" +
            "- login(username, password)\n" +
            "- logout()\n" +
            "- insertLimitOrder(type, size, limitPrice)\n" +
            "- insertMarketOrder(type, size)\n" +
            "- insertStopOrder(type, size, stopPrice)\n" +
            "- cancelOrder(orderID)\n" +
            "- getPriceHistory(month)\n" +
            "- showOrderBook()\n" +
            "- showStopOrders()\n" +
            "- help - Show this help message";

    // Dati utente
    private static String userName;
    private static String password;
    private static String message;

    public static String getConfigFile() {
        return configFile;
    }

    // Classe interna per dati condivisi tra thread
    public static class SharedData {
        public AtomicBoolean isLogged = new AtomicBoolean(false);       //Flag per indicare se l'utente è loggato
        public AtomicBoolean isClosed = new AtomicBoolean(false);       //Flag per indicare se la connessione è chiusa
        public AtomicBoolean loginError = new AtomicBoolean(false);     //Flag per indicare errori di login
        public AtomicBoolean isShuttingDown = new AtomicBoolean(false); //Flag per chiusura del client
        public volatile int UDPport = 0;                                             //Porta UDP assegnata dal server 
    }

    public static void main(String[] args) throws Exception{
        SharedData sharedData = new SharedData();   //Istanzia dati condivisi
        readConfig(); //Legge la configurazione del client
    
        //Thread per stampa sulla CLI
        Printer printer = new Printer();
        try (DatagramSocket UDPsocket = new DatagramSocket()) {
        TCPsocket = new Socket(hostname, TCPport); //Apertura socket TCP per connettersi al server
           
            //Apertura stream di input e output
            in = new BufferedReader(new InputStreamReader(TCPsocket.getInputStream()));
            out = new PrintWriter(TCPsocket.getOutputStream(), true);
            //Thread per ricezione messaggi dal server
            receiver = new Thread(new ReceiverClient(TCPsocket, in, printer, sharedData)); 
            receiver.start();

            //Shutdown per la gestione della terminazione con CTRL-C
            Runtime.getRuntime().addShutdownHook(new Thread(){
                public void run(){
                    if(sharedData.isShuttingDown.compareAndSet(false, true)){
                        //creazione file GSON da inviare al server
                        mes = new GsonMessage<>("logout", new Values()); // Creazione messaggio
                        message = gson.toJson(mes); // Creazione dell'oggetto Gson da mandare
                        out.println(message); // Invio del messaggio sullo stream
                        //out.flush();
                        closeConnection();
                    }
                }
            });

            System.out.println("Benvenuto, ecco cosa puoi fare\n" + helpMessage);

            //Ciclo per la lettura dei comandi
            while (!sharedData.isShuttingDown.get()) {
                try {
                    //Controllo prima di leggere l'input
                    if (sharedData.isShuttingDown.get() || sharedData.isClosed.get()) break;
                    // Controllo se ci sono dati disponibili 
                    if (System.in.available() <= 0 && !scanner.hasNextLine()) {
                        Thread.sleep(100);
                        continue;
                    }
                    //Controllo di sicurezza prima di leggere la linea
                    if (sharedData.isShuttingDown.get()) break;
                    String input = scanner.nextLine().trim(); //Lettura del comando
                    if (input == null || input.isEmpty()) continue;

                    printer.inputReceived(); //Indica che l'input è stato ricevuto

                    if (isValidCommand(input)) { //Se il comando è valido
                        String[] tokens = input.split("[(),\\s]+"); //Tokenizzazione del comando
                        String command = tokens[0].trim().toLowerCase();  //Estrazione del comando principale

                        switch (command) {
                            //Messaggio per visualizzare tutti i comandi
                            case "help":
                                System.out.println("Benvenuto, ecco cosa puoi fare\n" + helpMessage);
                                break;

                            // Comando registrazione
                            case "register":
                                if (tokens.length == 3) {
                                    userName = tokens[1].trim();
                                    password = tokens[2].trim();
                                    // Creazione messaggio 
                                    mes = new GsonMessage<>("register", new GsonUser(userName, password));
                                    // Serializzazione messaggio: oggetto Gson da mandare
                                    message = gson.toJson(mes); 
                                    // Invio messaggio
                                    out.println(message);
                                } else {
                                    // Messaggio di errore
                                    printer.print("Formato non valido. Prova: register username password");
                                }
                                break;

                            // Comando aggiornamento credenziali
                            case "updatecredentials":
                                if (tokens.length == 4) {
                                    userName = tokens[1].trim();
                                    String oldPassword = tokens[2].trim();
                                    String newPassword = tokens[3].trim();
                                    mes = new GsonMessage<>("updateCredentials", new GsonCredentials(userName, oldPassword, newPassword));
                                    message = gson.toJson(mes);
                                    out.println(message);
                                } else {
                                    printer.print("Formato non valido. Prova: updateCredentials username oldPassword newPassword");
                                }
                                break;

                            // Comando login
                            case "login":
                                if (tokens.length == 3) {
                                    userName = tokens[1].trim();
                                    password = tokens[2].trim();
                                    mes = new GsonMessage<>("login", new GsonUser(userName, password));
                                    message = gson.toJson(mes);
                                    out.println(message);
                                    /*
                                    while (!udpMessage) {
                                        // Messaggio mandato solo se l'utente si è loggato con successo
                                        if (sharedData.isLogged.get() == true) {
                                            sendUDPmessage(UDPsocket, printer, sharedData);
                                            udpMessage = true;
                                        }
                                        if (sharedData.loginError.get() == true){
                                            break;
                                        }
                                    } */

                                    while (!sharedData.isShuttingDown.get() && !sharedData.isClosed.get()) {
                                        if (sharedData.isLogged.get() && sharedData.UDPport > 0) {
                                            // Ora la porta UDP deve essere stata ricevuta via TCP
                                                // Invia pacchetto UDP di handshake
                                                sendUDPmessage(UDPsocket, printer, sharedData);
                                                udpMessage = true;
                                        
                                            break; // Login completato
                                        }
                                        if (sharedData.loginError.get()) break; // Login fallito
                                        Thread.sleep(50); // Evita busy-waiting
                                    }
                                } else {
                                    printer.print("Formato non valido. Prova: login username password");
                                }
                                break;

                            // Comando logout
                            case "logout":
                                if (sharedData.isShuttingDown.compareAndSet(false, true)) {
                                    mes = new GsonMessage<>("logout", new Values());
                                    message = gson.toJson(mes);
                                    out.println(message);
                                    out.flush();
                                    
                                   // while (!sharedData.isClosed.get()) {}
                                    long start = System.currentTimeMillis();
                                    //Se dopo 2 secondi non si è chiusa la connessione, forzo la chiusura
                                        while (!sharedData.isClosed.get() && System.currentTimeMillis() - start < 2000) { 
                                            Thread.sleep(50); //Pausa breve per evitare busy-waiting
                                        }
                                    closeConnection();
                                    return;
                                }
                                break;

                            // Comando insertLimitOrder
                            case "insertlimitorder":
                                if (tokens.length == 4) {
                                    try {
                                        String type = tokens[1].toLowerCase();
                                        int size = Integer.parseInt(tokens[2]);
                                        int limitPrice = Integer.parseInt(tokens[3]);
                                        if (type.equals("ask") || type.equals("bid")) {
                                            if (!sharedData.isLogged.get()) {
                                                printer.print("Non sei loggato");
                                                printer.promptUser();
                                            } else {
                                                mes = new GsonMessage<>("insertLimitOrder", new GsonLimitStopOrder(type, size, limitPrice));
                                                message = gson.toJson(mes);
                                                out.println(message);
                                            }
                                        } else {
                                            printer.print("Tipo di ordine sconosciuto: usa 'ask' o 'bid'");
                                            printer.promptUser();
                                        }
                                    } catch (NumberFormatException e) {
                                        printer.print("Size o limitPrice: devono essere numeri");
                                        printer.promptUser();
                                    }
                                } else {
                                    printer.print("Formato non valido. Prova: insertLimitOrder ask|bid size limitPrice");
                                    printer.promptUser();
                                }
                                break;
                            
                            case "insertmarketorder":
                                if (tokens.length == 3) {
                                    try {
                                        String type = tokens[1].toLowerCase();
                                        int size = Integer.parseInt(tokens[2]);
                                        if (type.equals("ask") || type.equals("bid")) {
                                            if (!sharedData.isLogged.get()) {
                                                printer.print("Non sei loggato");
                                                printer.promptUser();
                                            } else {
                                                mes = new GsonMessage<>("insertMarketOrder", new GsonMarketOrder(type, size));
                                                message = gson.toJson(mes);
                                                out.println(message);
                                            }
                                        } else {
                                            printer.print("Tipo di ordine sconosciuto: usa 'ask' o 'bid'");
                                            printer.promptUser();
                                        }
                                    } catch (NumberFormatException e) {
                                        printer.print("Size: deve essere un numero");
                                        printer.promptUser();
                                    }
                                } else {
                                    printer.print("Formato non valido. Prova: insertMarketOrder ask|bid size");
                                    printer.promptUser();
                                }
                            break;

                            case "insertstoporder":
                                if (tokens.length == 4) {
                                    try {
                                        String type = tokens[1].toLowerCase();
                                        int size = Integer.parseInt(tokens[2]);
                                        int stopPrice = Integer.parseInt(tokens[3]);
                                        if (type.equals("ask") || type.equals("bid")) {
                                            if (!sharedData.isLogged.get()) {
                                                printer.print("Non sei loggato");
                                                printer.promptUser();
                                            } else {
                                                mes = new GsonMessage<>("insertStopOrder", new GsonLimitStopOrder(type, size, stopPrice));
                                                message = gson.toJson(mes);
                                                out.println(message);
                                            }
                                        } else {
                                            printer.print("Tipo di ordine sconosciuto: usa 'ask' o 'bid'");
                                            printer.promptUser();
                                        }
                                    } catch (NumberFormatException e) {
                                        printer.print("Size o stopPrice");
                                        printer.promptUser();
                                    }
                                } else {
                                    printer.print("Formato non valido. Prova: insertStopOrder ask|bid size stopPrice");
                                    printer.promptUser();
                                }
                            break;

                            case "cancelorder":
                                if (tokens.length == 2) {
                                    try {
                                        int orderID = Integer.parseInt(tokens[1]);
                                        GsonResponseOrder responseOrder = new GsonResponseOrder();
                                        responseOrder.setResponseOrder(orderID);
                                        if (!sharedData.isLogged.get()) {
                                            printer.print("Non sei loggato");
                                            printer.promptUser();
                                        } else {
                                            mes = new GsonMessage<>("cancelOrder", responseOrder);
                                            message = gson.toJson(mes);
                                            out.println(message);
                                        }
                                    } catch (NumberFormatException e) {
                                        printer.print("orderID deve essere un numero");
                                        printer.promptUser();
                                    }
                                } else {
                                    printer.print("Formato non valido.");
                                    printer.promptUser();
                                }
                            break;
                            
                            case "getpricehistory":
                                String date = tokens[1];
                                // Valido data
                                if (date.length() == 6){
                                    int month = Integer.parseInt(date.substring(0, 2));
                                    int year = Integer.parseInt(date.substring(2));

                                    if (month > 0 && month <= 12 && year <= 2025) {
                                        if (!sharedData.isLogged.get()) {
                                            printer.print("Non sei loggato");
                                            printer.promptUser();
                                        } else {
                                            mes = new GsonMessage<>("getPriceHistory", new GsonAskHistory (date));
                                            message = gson.toJson(mes);
                                            out.println(message);
                                        }
                                    } else {
                                        printer.print("Data non valida");
                                        printer.promptUser();
                                    }
                                } else {
                                    printer.print("Formato non valido. Prova ad usare il formato MMYYYY");
                                    printer.promptUser();
                                }
                            break;

                            case "showorderbook":
                                if (!sharedData.isLogged.get()) {
                                    printer.print("Non sei loggato");
                                    printer.promptUser();
                                } else {
                                    mes = new GsonMessage<>("showOrderBook", new Values());
                                    message = gson.toJson(mes);
                                    out.println(message);
                                }
                            break;

                            case "showstoporders":
                                if (!sharedData.isLogged.get()) {
                                    printer.print("Non sei loggato");
                                    printer.promptUser();
                                } else {
                                    mes = new GsonMessage<>("showStopOrders", new Values());
                                    message = gson.toJson(mes);
                                    out.println(message);
                                }
                            break;
                            default:
                                printer.print("Comando non riconosciuto");
                                printer.promptUser();
                                break;
                    }    
                    } else{
                        System.out.println("Comando non valido, riprova");
                        printer.promptUser();
                    }
                } catch (NoSuchElementException e){
                    // Eccezione lanciata quando scanner.nextLine() è interrotto da CTRL+C
                    if(sharedData.isShuttingDown.get()){ // Se si sta già chiudendo questa eccezione viene ignorata
                       
                        break;
                    }
                    if(!sharedData.isClosed.get()){
                        System.err.println("[MAINCLIENT] Input interrotto: " + e.getMessage());
                        printer.promptUser();
                    }
                } catch (Exception e){
                    if(sharedData.isShuttingDown.get() || sharedData.isClosed.get()){
                        // Se si sta già chiudendo questa eccezione viene ignorata
                        break;
                    }
                    // Stampa l'errore
                    System.err.println("[MAINCLIENT] Errore " + e.getMessage() + " Causa: " + e.getCause());
                    printer.promptUser();
                }
                
            }
        } catch (SocketException e){
            //Eccezione lanciata quando la socket è chiusa inaspettatamente
            if(!sharedData.isShuttingDown.get() && !sharedData.isClosed.get()){ //Se non si sta già chiudendo
                System.err.println("[MAINCLIENT] Errore socket : " + e.getMessage());
            }
            printer.promptUser();

        } catch(Exception e){
            //Eccezione lanciata in caso di errori
            if(!sharedData.isShuttingDown.get() && !sharedData.isClosed.get()){ //Se non si sta già chiudendo
                System.out.println("[MAINCLIENT] Errore " + e.getMessage() + " Causa: " + e.getCause());
            }
            printer.promptUser();
        }
        //Chiusura connessione se non è già in corso
        if(!sharedData.isShuttingDown.get()){
            System.out.println("Chiusura client...");
            sharedData.isShuttingDown.set(true);
            closeConnection();
        }
        
    }
    
    //Metodo per chiusura di tutti i socket e i thread
    public static void closeConnection() {
        try {
            //Se i thread sono attivi 
            if (receiver != null && receiver.isAlive()) {
                receiver.interrupt(); //Interrompe il thread
                try { receiver.join(500); //Attende la terminazione 
                } catch (InterruptedException ignored) {} //Ignora interruzioni durante l'attesa
            }
            if (UDPreceiver != null && UDPreceiver.isAlive()) {
                UDPreceiver.interrupt();
                try { UDPreceiver.join(500); //Attende la terminazione
                } catch (InterruptedException ignored) {} //Ignora interruzioni durante l'attesa
            }
            //Chiude il socket TCP se non è già chiuso
            if (TCPsocket != null && !TCPsocket.isClosed()) TCPsocket.close();
            if (in != null) in.close(); //Chiude lo stream di input 
            if (out != null) {          //Se lo stream è aperto
                out.flush();            //Svuota il buffer
                out.close();            //Chiude lo stream di output
            }
        }catch (Exception e) {
            //Eccezione lanciata in caso di errori
            System.err.println("[MAINCLIENT] Errore durante la chiusura della connessione: " + e.getMessage());
        }
    }

    //Metodo per invio dei messaggi UDP al server per comunicazione asincrona
    public static void sendUDPmessage(DatagramSocket UDPsocket, Printer printer, SharedData sharedData) {
        try {
            InetAddress address = InetAddress.getByName(hostname);
            //Creazione pacchetto UDP 
            DatagramPacket packet = new DatagramPacket(new byte[1], 1, address, sharedData.UDPport);
            UDPsocket.send(packet); //Invio pacchetto
            UDPreceiver = new Thread(new UDP(UDPsocket, in, printer)); //Thread per ricezione 
            UDPreceiver.start();
        } catch (Exception e) {
            //Eccezione lanciata in caso di errori
            System.err.println("[MAINCLIENT] Errore durante l'invio del messaggio UDP: " + e.getMessage());
            printer.promptUser();
        }
    }

    //Metodo per verificare la validità dei comandi passati
    public static boolean isValidCommand(String input) {
        for (String pattern : validCommands) { 
            if (input.matches(pattern)) return true; //Se il comando corrisponde a uno dei pattern allora è valido
        }
        return false;
    }

    //Metodo per la lettura del file di configurazione del client
    public static void readConfig() throws FileNotFoundException, IOException {
        InputStream input = new FileInputStream(configFile); //Apre il file di configurazione
        Properties prop = new Properties();                  //Crea oggetto
        prop.load(input);                                   //Carica le proprietà dal file
        //Legge e converte la porta e l'hostname
        TCPport = Integer.parseInt(prop.getProperty("TCPport")); 
        hostname = prop.getProperty("hostname");
        input.close();
    }
}