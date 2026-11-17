package com.creatormc.judgementdaymod.setup;

/** A registered block or item, read as Forge's RegistryObject is. */
public final class Registered<T> {
        private final T value;

        Registered(T value) {
                this.value = value;
        }

        public T get() {
                return value;
        }
}
