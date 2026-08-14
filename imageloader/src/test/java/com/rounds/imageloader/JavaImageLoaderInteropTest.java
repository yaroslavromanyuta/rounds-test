package com.rounds.imageloader;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.widget.ImageView;

import com.rounds.imageloader.testing.MainDispatcherRule;

import org.junit.Rule;
import org.junit.Test;

/**
 * Proves the public API is genuinely usable from Java rather than merely visible to it.
 *
 * Nothing here creates a coroutine scope, passes a suspending function, unwraps a Kotlin
 * {@code Result} or touches a Flow — a Java consumer sees a static factory and two ordinary
 * methods. The rule only exists because a JVM unit test has no Android main looper; production
 * Java callers do not need it.
 */
public class JavaImageLoaderInteropTest {

    private static final int PLACEHOLDER_RES = 4242;

    @Rule
    public final MainDispatcherRule mainDispatcherRule = new MainDispatcherRule();

    @Test
    public void javaConsumerCanLoadAndClearAnImageView() {
        ImageLoader loader = ImageLoader.create();
        ImageView imageView = mock(ImageView.class);

        assertNotNull(loader);

        // An unresolvable URL keeps the test off the network; the load fails silently and the
        // placeholder stays, which is exactly the documented failure behaviour.
        loader.load("not a url", PLACEHOLDER_RES, imageView);

        verify(imageView).setImageResource(PLACEHOLDER_RES);

        loader.clear(imageView);
    }

    @Test
    public void javaConsumerCanLoadWithoutAPlaceholder() {
        ImageLoader loader = ImageLoader.create();
        ImageView imageView = mock(ImageView.class);

        // The placeholder is genuinely optional from Java: a real overload, not a Kotlin default
        // argument that only Kotlin callers can omit.
        loader.load("not a url", imageView);

        verify(imageView).setImageDrawable(null);
    }
}
