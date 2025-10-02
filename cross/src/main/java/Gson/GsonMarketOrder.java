package Gson;
//Classe usata per passare al server i dati per inserire un marketOrder
public class GsonMarketOrder extends Values {
    public String type;
    public int size;

    public GsonMarketOrder(String type, int size){
        this.type = type;
        this.size = size;
    }
    public String getType(){
        return this.type;
    }
  
    public int getSize(){
        return this.size;
    }

    public String toString() {
        return "{type ='" + this.type + "', size ='" + this.size + "}";
    }

}
