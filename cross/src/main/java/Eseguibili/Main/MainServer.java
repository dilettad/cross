package Eseguibili.Main;

//Import delle librerie
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

//Import delle classi
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import Eseguibili.Server.*;
import OrderBook.*; 
//import Eseguibili.Server.Worker; 

//Classe principale: gestione della connessioni dei client
public class MainServer {
    //Socket e stream
    public static final String configFile = "server.properties"; // File di configurazione
    public static int TCPport;                                   // Porta TCP per le connessioni client
    public static int UDPport;                                   // Porta base UDP per le comunicazioni asincrone
    public static String hostname;                               // Nome host del server
    public static int maxDelay;                                  // Ritardo massimo per timeout
    public static ServerSocket serverSocket;                     // Server socket TCP
    
    public static ConcurrentLinkedQueue<Worker> workerList = new ConcurrentLinkedQueue<>(); // Lista di tutti i worker attivi
    public static ConcurrentHashMap<String,Tupla> userMap = new ConcurrentHashMap<>();      // Mappa degli utenti
    private static final ConcurrentSkipListMap<String, SockMapValue> socketMap = new ConcurrentSkipListMap<>(); // Mappa dei socket attive

    //bidMap: ordine di acquisto, ordinati in modo decrescente
    public static ConcurrentSkipListMap<Integer, Bookvalue> bidMap = new ConcurrentSkipListMap<>(Collections.reverseOrder());
    //askMap: ordine di vendita, ordinati in modo crescente
    public static ConcurrentSkipListMap<Integer, Bookvalue> askMap = new ConcurrentSkipListMap<>();
    //Lista degli Stop Orders
    public static ConcurrentLinkedQueue<StopValue> stopOrders;
    public static OrderBook orderBook = new OrderBook(askMap, 0, new ConcurrentLinkedQueue<StopValue>(), bidMap);

    //Thread pool per gestire i worker
    public static final ExecutorService executorService = Executors.newCachedThreadPool();
    
    //Metodo principale
    public static void main (String [] args) throws Exception {
        //Lettura dei parametri
        readConfig();
        try{
            serverSocket = new ServerSocket(TCPport);
            // Viene associato un handler per gestire la terminazione con CTRL+C
            Runtime.getRuntime().addShutdownHook(new Thread(){
                public void run(){
                    System.out.println("\nChiusura in corso...");
                    shutdown();
                }
            });
            loadUserMap(); //Lettura della userMap
            loadOrderBook(); // Lettura dell'orderBook

            System.out.printf("[MAINSERVER]: In ascolto sulla porta %d\n",TCPport);
            int i = 1;
            //Ciclo per accettare connessioni
            while(true){
                Socket receivedSocket = serverSocket.accept(); //Accettazione della connessione
                // Creazione del worker che si occuperà di gestire il client
                Worker worker = new Worker(receivedSocket,userMap,orderBook,socketMap,UDPport+i);
                workerList.add(worker); // Aggiunta del thread alla lista che mantiene tutti worker
                executorService.execute((Runnable) worker); // Esecuzione del thread
                i++; // Incremento della porta UDP per il prossimo client
            }
        }catch (SocketException e){
            // La accept() è stata interrotta: questa eccezione viene ignorata
        } catch (Exception e){ //Eccezione lanciata per errori generale
            System.err.println("[MAINSERVER] Errore: " + e.getMessage());
        }
    }
    
    //Metodo per aggiornare tutti i gestori dei timeout per tutti i worker attivi
    public static void updateTimeoutHandlers() {
        for (Worker worker : workerList) {
            worker.updateTimeoutHandler();
        }
    }
    
    //Metodo per caricare la mappa degli utenti dal file JSON
    public static void loadUserMap(){
        try (JsonReader reader = new JsonReader(new FileReader("src/Json/userMap.json"))) {
            reader.beginObject();
            while (reader.hasNext()){ 
                String name = reader.nextName();
                Tupla value = new Gson().fromJson(reader, Tupla.class);  
                userMap.put(name, value);  //Inserisce nella mappa utenti

            }
            reader.endObject();
        }
        catch (EOFException e){
            //Eccezione lanciata se il file è vuoto
            System.out.println("[MAINSERVER] userMap.json è vuoto, nessun utente caricato.");
            return;
        } catch (Exception e) {
            //Eccezione lanciata per errori nel caricamento
            System.err.println("[MAINSERVER] Errore durante il caricamento di userMap.json: " + e.getMessage());
            System.exit(0);
        }
    }
    public static void updateJsonUsermap(ConcurrentHashMap<String,Tupla> userMap){
        try(BufferedWriter writer = new BufferedWriter(new FileWriter("src/Json/userMap.json"))){
            Gson g = new GsonBuilder().setPrettyPrinting().create();
            writer.write(g.toJson(userMap));

        } catch (Exception e){
            System.err.printf("[WORKER] updateJsonUsermap %s \n",e.getMessage());
        }
    }

    //Metodo che carica l'orderBook dal file JSON
    public static void loadOrderBook(){
        try (JsonReader reader = new JsonReader(new FileReader("src/Json/orderBook.json"))) {
           Gson gson = new Gson();
           reader.beginObject();

           //Ciclo per la lettura
            while (reader.hasNext()){
                String name = reader.nextName();
                // Se askMap -> mappa degli ordini di vendita
                if (name.equals("askMap")) {
                    reader.beginObject();
                    ConcurrentSkipListMap<Integer, Bookvalue> askMap = orderBook.askMap;

                    while (reader.hasNext()){
                        int price = Integer.parseInt(reader.nextName()); 
                        Bookvalue value = gson.fromJson(reader, Bookvalue.class); 
                        askMap.put(price, value);
                    }
                    reader.endObject();
                //Se bidMap -> mappa degli ordini di acquisto
                } else if (name.equals("bidMap")) {
                    reader.beginObject();
                    ConcurrentSkipListMap<Integer, Bookvalue> bidMap = orderBook.bidMap;

                    while (reader.hasNext()){
                        int price = Integer.parseInt(reader.nextName());
                        Bookvalue value = gson.fromJson(reader, Bookvalue.class); 
                        bidMap.put(price, value);
                    }
                    reader.endObject();
                // Se spread -> valore dello spread
                } else if (name.equals("spread")){
                    orderBook.spread = reader.nextInt();
                // Se lastOrderID -> valore dell'ultimo ID ordine    
                } else if(name.equals("lastOrderID")){
                    orderBook.lastOrderID = reader.nextInt();
                // Se stopOrders -> lista degli ordini di stop    
                } else if(name.equals("stopOrders")){
                    reader.beginArray();
                    orderBook.stopOrders = new ConcurrentLinkedQueue<StopValue>();
                    
                    // Gli elementi della lista di stopOrders vengono saltati
                    while(reader.hasNext()){
                        reader.skipValue();
                    }
                    reader.endArray();
                }
            }
            reader.endObject();
        } catch (EOFException e) {
            //Eccezione lanciata se l'orderBook è vuoto
            System.out.println("[MAINSERVER] orderBook.json è vuoto, nessun ordine caricato.");
            return;
        } catch (Exception e) {
            //Eccezione lanciata nel caricamento dell'orderBook
            System.err.println("[MAINSERVER] Errore caricamento orderBook.json: " + e.getMessage());
            System.exit(0);
        }
    }  

    //Metodo che gestisce la chiusura controllata del server
    private static void shutdown(){
       try {
            if (serverSocket !=null && !serverSocket.isClosed()) { //Se il socket non è già stato chiuso lo chiude
                serverSocket.close();
            }
        } catch (IOException e) {
            //Eccezione lanciata in caso di errore nella chiusura del socket
            System.err.println("[MAINSERVER] Errore chiusura del socket server: " + e.getMessage());
        }
        //Notifica a tutti i worker della chiamata
        if (!workerList.isEmpty()) {
            for (Worker worker : workerList) {
                worker.shutdown();
            }
        }
        //Imposto la flag dell'utente, isLogged a false
        for (Tupla user : userMap.values()) {
            //System.out.println("Imposto isLogged = false per l'utente");
            user.setLogged(false);
           
        }
        //Salvataggio della userMap su file JSON
        updateJsonUsermap(userMap);


        //Terminare il pool di thread
        executorService.shutdown(); //Non nuovi task verranno accettati
        try {
            //Attesa terminazione dei thread esistenti
            if (!executorService.awaitTermination(maxDelay, TimeUnit.SECONDS)) {
                executorService.shutdownNow(); //Forza la terminazione dei thread dopo il timeout
            }
        } catch (InterruptedException e) { //Eccezione lanciata in caso di errore nell'attesa
            executorService.shutdownNow(); //Forza la terminazione dei thread
        }
        System.out.println("[MAINSERVER] Server chiusura completata.");
    } 

    //Metodo per leggere il file di configurazione del server
    public static void readConfig() throws FileNotFoundException, IOException{
        //Apre il file e crea oggetto Properties
        InputStream input = new FileInputStream(configFile);
        Properties prop = new Properties();
        prop.load(input); //Carica le proprietà dal file
        //Legge le proprietà
        TCPport = Integer.parseInt(prop.getProperty("TCPport"));
        maxDelay = Integer.parseInt(prop.getProperty("maxDelay"));
        UDPport = Integer.parseInt(prop.getProperty("UDPport"));
        hostname = prop.getProperty("hostname");
        input.close(); //Chiude il file
    } 
}