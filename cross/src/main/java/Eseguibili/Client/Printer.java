package Eseguibili.Client;

// Importazione per la gestione coda e thread-safe
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// Classe responsabile della gestione della stampa dei messaggi in modo asincrono, usa una coda per gestire i messaggi e un thread per la stampa
public class Printer {
    //Coda che contine i messaggi da stampare
    private final BlockingQueue<String> messageQueue =  new LinkedBlockingQueue<>();
    //Thread dedicato che preleva e stampa i messaggi
    private final Thread printerThread;
    //Garantisce la visibilità corretta tra thread diversi
    private volatile boolean readyToPrint = true;

    //Costruttore: inizializza e avvia il thread di stampa
    public Printer(){
        printerThread = new Thread(() -> {
                try { 
                    //
                    while(!Thread.currentThread().isInterrupted()) {
                        // Attende e rimuove un messaggio dalla coda, bloccando se necessario
                        String message = messageQueue.take();
                        // Stampa il messaggio
                        System.out.println(message);
                        
                        //Se non ci sono messaggi mostra il prompt 
                        if (readyToPrint && messageQueue.isEmpty()) {
                            System.out.print("> ");
                            System.out.flush();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Ripristina lo stato di interruzione
                }

        });
        printerThread.setDaemon(true); // Imposta il thread come daemon per terminare con l'applicazione
        printerThread.start(); // Avvia il thread di stampa
    }

    // Metodo per aggiungere un messaggio alla coda
    public void print(String message) {
            try {
                messageQueue.put(message); // Aggiunge il messaggio alla coda, bloccando se necessario
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

    /*  public static void main(String[] args) {
        Printer printer = new Printer();
        printer.start(); // Avvia il thread di stampa
        printer.print("Printer funziona");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            printer.shutdown();
        }
    }
    */
}
