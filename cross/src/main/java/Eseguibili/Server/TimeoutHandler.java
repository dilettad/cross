package Eseguibili.Server;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;

import OrderBook.OrderBook;
import OrderBook.StopValue;


//Thread che gestisce il timeout dei client connessi al server. 
//Monitora l'attività degli utenti e chiude le connessioni inattive, gestendo le eccezioni per gli utenti che attendono uno o più stop order

public class TimeoutHandler implements Runnable { 
    private static final int TIMEOUT_MINUTES = 10;                              //Timeout di 10 minuti
    private static final long TIMEOUT_MILLIS = TIMEOUT_MINUTES * 60 * 1000;     //Timeout in millisecondi
    public String user = null;
    private final Worker.SharedState sharedState;                               //Stato condiviso tra Worker e TimeoutHandler

    // Costruttore inizializza lo stato condiviso
    public TimeoutHandler(Worker.SharedState sharedState){
        this.sharedState = sharedState;
    }

    //Imposta il timestamp dell'ultima attività dell'utente
    public void setTimestamp(long timestamp) {
        sharedState.lastActivity = timestamp;
    }

    //Imposta il nome utente associato a questo handler
    public void setUsername(String user) {
        this.user = user;
    }

    //Metodo che converte e stampa un timestamp in formato orario leggibile
    public void printTime (long millis){
        LocalTime orario = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime(); //Converte i millesecondi in oggetto LocalTime
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");                //Definisce il formato dell'orario
        System.out.println("Ultima attività di " + user + ": " + orario.format(formatter));          
    }

    //Aggiorna la lista degli stop orders nello stato condiviso
    public void updateStopOrders(ConcurrentLinkedQueue<StopValue> stopOrders) {
        // Se la lista degli stop orders è vuota, la inizializza
        if(sharedState.stopOrders == null) {
            sharedState.stopOrders = new ConcurrentLinkedQueue<>(stopOrders);
        } else {
            // Se la lista degli stop orders esiste, la svuolta e la riempie con i nuovi valori
            sharedState.stopOrders.clear();
            sharedState.stopOrders.addAll(stopOrders);
        }
    }

    // Sincronizza lo stato corrente con l'orderBook
    public void syncWithOrderBook(OrderBook orderBook) {
        updateStopOrders(orderBook.stopOrders);
    }

    @Override
    //Metodo principale che monitora l'attività del client
    public void run(){
        //Ciclo infinito finché il handler è attivo
        while(sharedState.runningHandler.get()){
            try {
                //Controllo se client è inattivo
                long currentTime = System.currentTimeMillis();
                //Se l'utente ha superato il timeout
                if (currentTime - sharedState.lastActivity > TIMEOUT_MILLIS) {
                    if(user == null){                               //Utente non loggato
                        System.out.println("[TIMEOUTHANDLER] Client inattivo da più di " + TIMEOUT_MINUTES + " minuti. Chiusura connessione...");
                        sharedState.activeUser.set(false);
                        break;                                      //Esce dal ciclo e termina il thread
                    } else {
                        //Utente loggato, controlla se è negli stopOrders
                        boolean contains = false;
                        if (sharedState.stopOrders != null) { 
                            for (StopValue stop : sharedState.stopOrders) {
                                if (stop.username.equals(user)) {
                                    contains = true;
                                    break;
                                }
                            }
                        }
                        if (!contains){ //Se utente non è in stopOrders, chiude la connessione
                            System.out.println("[TIMEOUTHANDLER] Client " + user + " non presente negli stop orders.");
                            sharedState.activeUser.set(false);
                        } else { //Se utente è in stopOrders, mantiene la connessione aperta
                            System.out.println("[TIMEOUTHANDLER] Client " + user + " in attesa di uno stopOrder. Connessione aperta.");
                        }
                    }
                    sharedState.runningHandler.set(false); //Termina il handler
                }
                //Controllo ogni 5 secondi
                Thread.sleep(5000);
                
            } catch (Exception e){ //Eccezione lanciata in caso di errore
                System.err.println("[TIMEOUTHANDLER] Errore in TimeoutHandler: " + e.getMessage() + " Causa: " + e.getCause());
            }
        }
        System.out.println("[TIMEOUTHANDLER] Handler di " + user + " terminato.");
    }
}