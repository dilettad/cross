package Eseguibili.Client;

// Importazione per la gestione coda concorrente thread-safe
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

//Classe per la gestione della stampa dei messaggi in modo asincrono
public class Printer {
    //Coda concorrente che conterrà i messaggi da stampare
    private final BlockingQueue<String> messageQueue =  new LinkedBlockingQueue<>();
    //Thread dedicato che stampa i messaggi presi dalla coda
    private final Thread printerThread;
    //Garantisce la visibilità corretta tra thread diversi
    private volatile boolean readyToPrint = true;

    //Costruttore: crea e avvia il thread di stampa
    public Printer(){
        //Inizializza il thread di stampa
        printerThread = new Thread(() -> {
                try { 
                    // Ciclo di stampa: finchè thread non viene interrotto continua
                    while(!Thread.currentThread().isInterrupted()) {
                        // Preleva il messaggio dalla coda (bloccato se vuota)
                        String message = messageQueue.take();
                        // Stampa il messaggio
                        System.out.println(message);
                        
                        //Se non ci sono messaggi mostra il prompt all'utente
                        if (readyToPrint && messageQueue.isEmpty()) {
                            System.out.print("> ");
                            System.out.flush();
                        }
                    }
                } catch (InterruptedException e) {
                    // Gestione dell'interruzione del thread: se interrotto, esce dal ciclo 
                    Thread.currentThread().interrupt(); // Ripristina lo stato di interruzione
                }

        });
        printerThread.setDaemon(true); // Imposta il thread come daemon per terminare con l'applicazione
        printerThread.start(); // Avvia il thread di stampa
    }

    // Metodo per aggiungere un messaggio alla coda
    public void print(String message) {
            try {
                messageQueue.put(message); // Aggiunge il messaggio alla coda, bloccando se necessario (put)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Ripristina lo stato di interruzione
            }
        
    }

    // Metodo per indicare che si attende l'input utente: stampa '>'
    public void promptUser(){
        readyToPrint = true;
        if(messageQueue.isEmpty()){
            System.out.print("> ");
            System.out.flush();
        }
    }
   
    //Metodo per indicare che l'input è ricevuto
    public void inputReceived() {
        readyToPrint = false;
    }

    // Metodo per interrompere il thread di stampa e terminare la gestione della coda
     public void shutdown() {
        printerThread.interrupt();
    }

}
