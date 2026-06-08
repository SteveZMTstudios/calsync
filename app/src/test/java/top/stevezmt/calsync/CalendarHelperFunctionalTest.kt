package top.stevezmt.calsync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CalendarHelperFunctionalTest {
    private lateinit var contentUrisMock: MockedStatic<ContentUris>
    private lateinit var fakeEventUri: Uri

    @Before
    fun setup() {
        contentUrisMock = mockStatic(ContentUris::class.java)
        fakeEventUri = mock()
        // stub parseId
        contentUrisMock.`when`<Long> { ContentUris.parseId(fakeEventUri) }.thenReturn(42L)
    }

    @After
    fun teardown() {
        contentUrisMock.close()
    }


    @Test
    fun insertEventUsesSelectedCalendarAndAddsReminder() {
        val resolver = mock<ContentResolver>()
        val context = TestContext().apply { this.resolver = resolver }
        val eventValues = argumentCaptor<ContentValues>()

        SettingsStore.setSelectedCalendar(context, 88L, "School")
        SettingsStore.setReminderMinutes(context, 15)

        val fakeReminderUri: Uri = mock()
        whenever(resolver.insert(anyOrNull(), any())).thenReturn(
            fakeEventUri, // events return
            fakeReminderUri   // reminders return
        )

        val id = CalendarHelper.insertEvent(
            context = context,
            title = "答辩",
            description = "毕业答辩",
            startMillis = 1000L,
            endMillis = 2000L,
            location = "教学楼"
        )

        assertEquals(42L, id)
        verify(resolver, times(2)).insert(anyOrNull(), eventValues.capture())
        assertEquals(88L, eventValues.allValues[0].getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals("答辩", eventValues.allValues[0].getAsString(CalendarContract.Events.TITLE))
        assertEquals("教学楼", eventValues.allValues[0].getAsString(CalendarContract.Events.EVENT_LOCATION))
        assertEquals(1, eventValues.allValues[0].getAsInteger(CalendarContract.Events.HAS_ALARM))
        assertEquals(42L, eventValues.allValues[1].getAsLong(CalendarContract.Reminders.EVENT_ID))
        assertEquals(15, eventValues.allValues[1].getAsInteger(CalendarContract.Reminders.MINUTES))
    }

    @Test
    fun insertEventFallsBackToPrimaryCalendarAndSkipsReminderWhenDisabled() {
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        val context = TestContext().apply { this.resolver = resolver }
        val eventValues = argumentCaptor<ContentValues>()

        val customEventUri: Uri = mock()
        contentUrisMock.`when`<Long> { ContentUris.parseId(customEventUri) }.thenReturn(55L)

        SettingsStore.setReminderMinutes(context, -1)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getLong(0)).thenReturn(123L)
        whenever(
            resolver.query(
                anyOrNull(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(cursor)
        whenever(resolver.insert(anyOrNull(), any())).thenReturn(customEventUri)

        val id = CalendarHelper.insertEvent(context, "开会", "例会", 1000L, null, null)

        assertEquals(55L, id)
        verify(resolver, times(1)).insert(anyOrNull(), eventValues.capture())
        assertEquals(123L, eventValues.firstValue.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertNull(eventValues.firstValue.getAsInteger(CalendarContract.Events.HAS_ALARM))
    }

    @Test
    fun insertEventReturnsNullWhenNoWritableCalendarExists() {
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        val context = TestContext().apply { this.resolver = resolver }

        whenever(cursor.moveToFirst()).thenReturn(false)
        whenever(
            resolver.query(
                anyOrNull(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(cursor)

        val id = CalendarHelper.insertEvent(context, "开会", "例会", 1000L, 2000L, null)

        assertNull(id)
        verify(resolver, never()).insert(anyOrNull(), any())
    }

    @Test
    fun listWritableCalendarsUsesDisplayNameThenAccountFallbackAndExtractsEmail() {
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        val context = TestContext().apply { this.resolver = resolver }

        whenever(cursor.moveToNext()).thenReturn(true, true, true, false)
        whenever(cursor.getLong(0)).thenReturn(1L, 2L, 3L)
        whenever(cursor.getString(1)).thenReturn("Primary", null, "Work")
        whenever(cursor.getString(2)).thenReturn("primary@example.com", null, "not-email")
        whenever(cursor.getString(3)).thenReturn("owner-1", "owner@example.com", "owner-work@example.org")
        whenever(
            resolver.query(
                anyOrNull(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(cursor)

        val calendars = CalendarHelper.listWritableCalendars(context)

        assertEquals(3, calendars.size)
        assertEquals(CalendarHelper.CalendarInfo(1L, "Primary", "primary@example.com"), calendars[0])
        assertEquals("Primary\nprimary@example.com", calendars[0].displayLabel)
        assertEquals(CalendarHelper.CalendarInfo(2L, "owner@example.com", "owner@example.com"), calendars[1])
        assertEquals(CalendarHelper.CalendarInfo(3L, "Work", "owner-work@example.org"), calendars[2])
    }
}
