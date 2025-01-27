package cheap

import org.junit.Test
import kotlin.test.assertEquals

private val LOCK = Any()

class EventListenerRepository {
    private var listeners: List<EventListener> = emptyList()

    fun addEventListener(event: Event, handler: () -> Unit): EventListener = synchronized(LOCK) {
        val listener = EventListener(event, handler)
        listeners = listeners + listener
        listener
    }

    fun invokeListeners(event: Event): Unit = synchronized(LOCK) {
        val activeListeners = listeners.filter { it.event == event && it.isActive }
        activeListeners.onEach { it.handler() }
    }

    enum class Event { A, B, C }
}

class EventListener(
    val event: EventListenerRepository.Event,
    val handler: () -> Unit,
    isActive: Boolean = true
) {
    var isActive: Boolean = isActive
        private set

    fun cancel(): Unit = synchronized(LOCK) {
        isActive = false
    }
}

class EventListenerRepositoryTest {

    @Test
    fun `should invoke proper handlers`() {
        val eventListenerRepository = EventListenerRepository()
        var a = 0
        var b = 0
        var c = 0

        eventListenerRepository.addEventListener(EventListenerRepository.Event.A) { a++ }
        eventListenerRepository.addEventListener(EventListenerRepository.Event.B) { b++ }
        eventListenerRepository.addEventListener(EventListenerRepository.Event.C) { c++ }

        assertEquals(0, a)
        assertEquals(0, b)
        assertEquals(0, c)

        eventListenerRepository.invokeListeners(EventListenerRepository.Event.A)

        assertEquals(1, a)
        assertEquals(0, b)
        assertEquals(0, c)

        eventListenerRepository.invokeListeners(EventListenerRepository.Event.B)
        eventListenerRepository.invokeListeners(EventListenerRepository.Event.B)

        assertEquals(1, a)
        assertEquals(2, b)
        assertEquals(0, c)

        eventListenerRepository.invokeListeners(EventListenerRepository.Event.C)
        eventListenerRepository.invokeListeners(EventListenerRepository.Event.C)
        eventListenerRepository.invokeListeners(EventListenerRepository.Event.C)

        assertEquals(1, a)
        assertEquals(2, b)
        assertEquals(3, c)
    }

    @Test
    fun `should allow setting more than one handler for an event`() {
        val eventListenerRepository = EventListenerRepository()
        var a = 0
        var b = 0
        var c = 0

        eventListenerRepository.addEventListener(EventListenerRepository.Event.A) { a++ }
        eventListenerRepository.addEventListener(EventListenerRepository.Event.A) { b++ }
        eventListenerRepository.addEventListener(EventListenerRepository.Event.A) { c++ }

        eventListenerRepository.invokeListeners(EventListenerRepository.Event.A)

        assertEquals(1, a)
        assertEquals(1, b)
        assertEquals(1, c)
    }

    @Test
    fun `should allow listener cancelation`() {
        val eventListenerRepository = EventListenerRepository()
        var a = 0

        val listener = eventListenerRepository.addEventListener(EventListenerRepository.Event.A) { a++ }
        listener.cancel()

        eventListenerRepository.invokeListeners(EventListenerRepository.Event.A)

        assertEquals(0, a)
    }
}
