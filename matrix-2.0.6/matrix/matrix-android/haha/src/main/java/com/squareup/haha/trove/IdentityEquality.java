package com.squareup.haha.trove;

class IdentityEquality<T> implements Equality<T> {
  @Override
  public boolean equals(T o1, T o2) {
    return o1 == o2;
  }
}
