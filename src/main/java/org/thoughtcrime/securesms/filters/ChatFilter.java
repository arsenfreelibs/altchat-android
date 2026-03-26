package org.thoughtcrime.securesms.filters;

import org.json.JSONException;
import org.json.JSONObject;

/** Represents a user-created chat folder (filter). Stored locally, never synced to server. */
public class ChatFilter {

  private final String id;
  private String name;

  public ChatFilter(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public JSONObject toJson() throws JSONException {
    JSONObject obj = new JSONObject();
    obj.put("id", id);
    obj.put("name", name);
    return obj;
  }

  public static ChatFilter fromJson(JSONObject obj) throws JSONException {
    return new ChatFilter(obj.getString("id"), obj.getString("name"));
  }
}
