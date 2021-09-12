/*
 * This file is part of adventure-platform, licensed under the MIT License.
 *
 * Copyright (c) 2018-2020 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.adventure.platform.bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.common.annotations.Beta;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

import net.kyori.adventure.platform.nms.accessors.Component_i_SerializerAccessor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.jetbrains.annotations.ApiStatus;
import net.kyori.adventure.platform.nms.accessors.ComponentAccessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static net.kyori.adventure.platform.bukkit.BukkitComponentSerializer.gson;
import static net.kyori.adventure.platform.bukkit.MinecraftReflection.lookup;

/**
 * A component serializer for {@code net.minecraft.server.<version>.IChatBaseComponent}.
 *
 * <p>Due to Bukkit version namespaces, the return type does not reflect the actual type.</p>
 *
 * <p>Color downsampling will be performed as necessary for the running server version.</p>
 *
 * <p>If not {@link #isSupported()}, an {@link UnsupportedOperationException} will be thrown on any serialize or deserialize operations.</p>
 *
 * @see #get()
 * @since 4.0.0
 */
@ApiStatus.Experimental // due to direct server implementation references
public final class MinecraftComponentSerializer implements ComponentSerializer<Component, Component, Object> {
  private static final MinecraftComponentSerializer INSTANCE = new MinecraftComponentSerializer();

  /**
   * Gets whether this serializer is supported.
   *
   * @return if the serializer is supported.
   * @since 4.0.0
   */
  public static boolean isSupported() {
    return SUPPORTED;
  }

  /**
   * Gets the component serializer.
   *
   * @return a component serializer
   * @since 4.0.0
   */
  public static @NotNull MinecraftComponentSerializer get() {
    return INSTANCE;
  }

  private static final @Nullable Class<?> CLASS_CHAT_COMPONENT = ComponentAccessor.getType();
  private static final AtomicReference<RuntimeException> INITIALIZATION_ERROR = new AtomicReference<>(new UnsupportedOperationException());

  private static final Object MC_TEXT_GSON;
  private static final MethodHandle TEXT_SERIALIZER_DESERIALIZE;
  private static final MethodHandle TEXT_SERIALIZER_SERIALIZE;

  static {
    Object gson = null;
    MethodHandle textSerializerDeserialize = null;
    MethodHandle textSerializerSerialize = null;

    try {
      if (CLASS_CHAT_COMPONENT != null) {
        // Chat serializer //
        final Class<?> chatSerializerClass = Component_i_SerializerAccessor.getType();
        if(chatSerializerClass != null) {
          final Field gsonField = Component_i_SerializerAccessor.getFieldGSON();
          if(gsonField != null) {
            gsonField.setAccessible(true);
            gson = gsonField.get(null);
          } else {
            // this is not relevant for us
            final Method[] declaredMethods = chatSerializerClass.getDeclaredMethods();
            final Method deserialize = Arrays.stream(declaredMethods)
              .filter(m -> Modifier.isStatic(m.getModifiers()))
              .filter(m -> CLASS_CHAT_COMPONENT.isAssignableFrom(m.getReturnType()))
              .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(String.class))
              .min(Comparator.comparing(Method::getName)) // prefer the #a method
              .orElse(null);
            final Method serialize = Arrays.stream(declaredMethods)
              .filter(m -> Modifier.isStatic(m.getModifiers()))
              .filter(m -> m.getReturnType().equals(String.class))
              .filter(m -> m.getParameterCount() == 1 && CLASS_CHAT_COMPONENT.isAssignableFrom(m.getParameterTypes()[0]))
              .findFirst()
              .orElse(null);
            if (deserialize != null) {
              textSerializerDeserialize = lookup().unreflect(deserialize);
            }
            if (serialize != null) {
              textSerializerSerialize = lookup().unreflect(serialize);
            }
          }
        }
      }
    } catch (final Throwable error) {
      INITIALIZATION_ERROR.set(new UnsupportedOperationException("Error occurred during initialization", error));
    }

    MC_TEXT_GSON = gson;
    TEXT_SERIALIZER_DESERIALIZE = textSerializerDeserialize;
    TEXT_SERIALIZER_SERIALIZE = textSerializerSerialize;
  }

  private static final boolean SUPPORTED = MC_TEXT_GSON != null || (TEXT_SERIALIZER_DESERIALIZE != null && TEXT_SERIALIZER_SERIALIZE != null);

  @Override
  public @NotNull Component deserialize(final @NotNull Object input) {
    if (!SUPPORTED) throw INITIALIZATION_ERROR.get();

    try {
      if(MC_TEXT_GSON != null) {
        final String element = MC_TEXT_GSON.getClass().getMethod("toJson", Object.class).invoke(MC_TEXT_GSON, input).toString();
        return gson().serializer().fromJson(element, Component.class);
      }
      return GsonComponentSerializer.gson().deserialize((String) TEXT_SERIALIZER_SERIALIZE.invoke(input));
    } catch (final Throwable error) {
      throw new UnsupportedOperationException(error);
    }
  }

  @Override
  public @NotNull Object serialize(final @NotNull Component component) {
    if (!SUPPORTED) throw INITIALIZATION_ERROR.get();

    if(MC_TEXT_GSON != null) {
      final String json = gson().serializer().toJson(component);
      try {
        return MC_TEXT_GSON.getClass().getMethod("fromJson", String.class, Class.class).invoke(MC_TEXT_GSON, json, CLASS_CHAT_COMPONENT);
      } catch(final Throwable error) {
        throw new UnsupportedOperationException(error);
      }
    } else {
      try {
        return TEXT_SERIALIZER_DESERIALIZE.invoke(gson().serialize(component));
      } catch (final Throwable error) {
        throw new UnsupportedOperationException(error);
      }
    }
  }
}
