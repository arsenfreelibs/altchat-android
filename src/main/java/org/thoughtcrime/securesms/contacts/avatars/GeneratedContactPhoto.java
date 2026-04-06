package org.thoughtcrime.securesms.contacts.avatars;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import com.amulyakhare.textdrawable.TextDrawable;
import java.util.regex.Pattern;
import org.thoughtcrime.securesms.R;

public class GeneratedContactPhoto implements FallbackContactPhoto {

  private static final Pattern PATTERN = Pattern.compile("[^\\p{L}\\p{Nd}\\p{P}\\p{S}]+");

  private final String name;

  public GeneratedContactPhoto(@NonNull String name) {
    this.name = name;
  }

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, true);
  }

  public Drawable asDrawable(Context context, int color, boolean roundShape) {
    int targetSize =
        context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);

    TextDrawable.IShapeBuilder builder =
        TextDrawable.builder()
            .beginConfig()
            .width(targetSize)
            .height(targetSize)
            .textColor(Color.WHITE)
            .bold()
            .toUpperCase()
            .endConfig();
    return roundShape
        ? builder.buildRound(getCharacter(name), color)
        : builder.buildRect(getCharacter(name), color);
  }

  private String getCharacter(String name) {
    String[] parts = name.trim().split("\\s+");
    String first = parts.length > 0 ? PATTERN.matcher(parts[0]).replaceAll("") : "";
    String second = parts.length > 1 ? PATTERN.matcher(parts[parts.length - 1]).replaceAll("") : "";

    if (!first.isEmpty() && !second.isEmpty()) {
      return new StringBuilder()
          .appendCodePoint(first.codePointAt(0))
          .appendCodePoint(second.codePointAt(0))
          .toString();
    }

    String cleanedName = PATTERN.matcher(name).replaceFirst("");
    if (cleanedName.isEmpty()) {
      return "#";
    } else {
      return new StringBuilder().appendCodePoint(cleanedName.codePointAt(0)).toString();
    }
  }

  @Override
  public Drawable asCallCard(Context context) {
    return AppCompatResources.getDrawable(context, R.drawable.ic_person_large);
  }
}
