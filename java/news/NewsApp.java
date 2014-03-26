package news; 

import java.util.HashSet; 
import java.util.Set; 
import javax.ws.rs.core.Application; 

public class NewsApp extends Application {

@Override 
    public Set<Class<?>> getClasses() {
        Set<Class<?>> classes = new HashSet<Class<?>>(); 
        classes.add(NewsService.class); 
        return classes; 
    }
}
