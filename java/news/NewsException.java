package news;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

public class NewsException extends WebApplicationException {
	
	private static final long serialVersionUID = 1L;

	public NewsException(String message) {
		super(Response.serverError().entity(message).build());
	}
}
