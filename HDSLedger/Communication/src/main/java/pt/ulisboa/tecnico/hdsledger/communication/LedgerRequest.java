package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;

public class LedgerRequest extends Message {
    
    // Serialized request
    private String message;
    // Signature of amount with client's private key
    private String clientSignature;

    public LedgerRequest(String senderId, Type type, String message, String signature) {
        super(senderId, type);
        this.message = message;
        this.clientSignature = signature;
    }

    public LedgerRequestBalance deserializeBalance() {
        return new Gson().fromJson(message, LedgerRequestBalance.class);
    }

    public LedgerRequestCreate deserializeCreate() {
        return new Gson().fromJson(message, LedgerRequestCreate.class);
    }

    public LedgerRequestTransfer deserializeTransfer() {
        return new Gson().fromJson(message, LedgerRequestTransfer.class);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getClientSignature() {
        return clientSignature;
    }

    public void setClientSignature(String clientSignature) {
        this.clientSignature = clientSignature;
    }
}
