package com.rounds.imageloader;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.widget.ImageView;

import com.rounds.imageloader.testing.MainDispatcherRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Proves the public API is genuinely usable from Java rather than merely visible to it.
 *
 * Nothing here creates a coroutine scope, passes a suspending function, unwraps a Kotlin
 * {@code Result} or touches a Flow — a Java consumer sees a static factory and four ordinary
 * methods, cache invalidation included. The dispatcher rule only exists because a JVM unit test has
 * no Android main looper; production Java callers do not need it.
 */
public class JavaImageLoaderInteropTest {

    private static final int PLACEHOLDER_RES = 4242;
    private static final String URL = "not a url";

    @Rule
    public final MainDispatcherRule mainDispatcherRule = new MainDispatcherRule();

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Context context;

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getCacheDir()).thenReturn(temporaryFolder.getRoot());
    }

    @Test
    public void javaConsumerCanLoadAndClearAnImageView() {
        ImageLoader loader = ImageLoader.create(context);
        ImageView imageView = mock(ImageView.class);

        assertNotNull(loader);

        // An unresolvable URL keeps the test off the network; the load fails silently and the
        // placeholder stays, which is exactly the documented failure behaviour.
        loader.load(URL, PLACEHOLDER_RES, imageView);

        verify(imageView).setImageResource(PLACEHOLDER_RES);

        loader.clear(imageView);
    }

    @Test
    public void javaConsumerCanLoadWithoutAPlaceholder() {
        ImageLoader loader = ImageLoader.create(context);
        ImageView imageView = mock(ImageView.class);

        // The placeholder is genuinely optional from Java: a real overload, not a Kotlin default
        // argument that only Kotlin callers can omit.
        loader.load(URL, imageView);

        verify(imageView).setImageDrawable(null);
    }

    @Test
    public void javaConsumerCanInvalidateAndClearTheCache() {
        ImageLoader loader = ImageLoader.create(context);

        // Both are plain void calls - no suspend function, no Job, no dispatcher to supply.
        loader.invalidate(URL);
        loader.clearCache();
    }
}
