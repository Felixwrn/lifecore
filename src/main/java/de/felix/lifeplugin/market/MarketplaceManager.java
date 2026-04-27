package de.felix.lifeplugin.market;

import org.json.JSONObject;
import java.net.URL;
import java.util.*;

public class MarketplaceManager {

    private static final String URL_STRING = "https://raw.githubusercontent.com/YOUR/REPO/main/marketplace.json";

    public static Map<String, JSONObject> load(){

        Map<String, JSONObject> map = new HashMap<>();

        try{
            Scanner sc = new Scanner(new URL(URL_STRING).openStream()).useDelimiter("\\A");
            JSONObject json = new JSONObject(sc.next());

            for(String key:json.keySet()){
                map.put(key,json.getJSONObject(key));
            }

        }catch(Exception e){
            e.printStackTrace();
        }

        return map;
    }
}
