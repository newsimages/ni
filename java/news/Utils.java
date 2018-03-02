package news;

import java.io.File;

public class Utils {

	public static String getDataDir() {
		String dir = System.getenv("OPENSHIFT_DATA_DIR");
		if(dir == null){
			dir = "data";
		}
		dir += File.separator  + ".ni";
		return dir;
	}
	
}
