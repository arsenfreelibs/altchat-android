package org.thoughtcrime.securesms.filters;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Represents the currently active filter in the chat list. Immutable. */
public final class ActiveFilter {

  public enum Type {
    ALL,
    UNREAD,
    CUSTOM
  }

  public static final ActiveFilter ALL = new ActiveFilter(Type.ALL, null);
  public static final ActiveFilter UNREAD = new ActiveFilter(Type.UNREAD, null);

  private final Type type;
  private final @Nullable String filterId;

  private ActiveFilter(@NonNull Type type, @Nullable String filterId) {
    this.type = type;
    this.filterId = filterId;
  }

  public static ActiveFilter custom(@NonNull String filterId) {
    return new ActiveFilter(Type.CUSTOM, filterId);
  }

  public Type getType() {
    return type;
  }

  public @Nullable String getFilterId() {
    return filterId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ActiveFilter)) return false;
    ActiveFilter other = (ActiveFilter) o;
    if (type != other.type) return false;
    if (type == Type.CUSTOM) {
      return filterId != null && filterId.equals(other.filterId);
    }
    return true;
  }

  @Override
  public int hashCode() {
    if (type == Type.CUSTOM && filterId != null) return 31 * type.hashCode() + filterId.hashCode();
    return type.hashCode();
  }
}
