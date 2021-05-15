package Supporting_Classes;

import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** */
public class WebSocketRequests extends WebSocketRequest<BatchResponse> {

    private List<Request<?, ? extends Response<?>>> requests;
    private long originId;

    public WebSocketRequests(
            CompletableFuture<BatchResponse> onReply,
            List<Request<?, ? extends Response<?>>> requests,
            Long originId) {

        super(onReply, BatchResponse.class);
        this.requests = requests;
        this.originId = originId;
    }

    public List<Request<?, ? extends Response<?>>> getRequests() {
        return requests;
    }

    public long getOriginId() {
        return originId;
    }
}
