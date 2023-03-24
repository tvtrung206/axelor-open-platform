import { useState, useMemo, useCallback, useEffect } from "react";

import { Box, CommandItemProps } from "@axelor/ui/core";
import { Scheduler, SchedulerEvent, View } from "@axelor/ui/scheduler";

import { CalendarView } from "@/services/client/meta.types";

import { useAsync } from "@/hooks/use-async";
import { useSession } from "@/hooks/use-session";

import { i18n } from "@/services/client/i18n";
import { l10n } from "@/services/client/l10n";
import { Criteria } from "@/services/client/data.types";

import { ViewProps } from "../types";

import DatePicker from "./components/date-picker";
import Filters from "./components/filters";
import { Filter } from "./components/types";

import { formatDate, getEventFilters, getTimes } from "./utils";

import styles from "./calendar.module.scss";
import { DEFAULT_COLOR } from "./colors";
import { ViewToolBar } from "@/view-containers/view-toolbar";
import { MaterialIconProps } from "@axelor/ui/src/icons/meterial-icon";
import { addDate } from "@/utils/date";

const { get: t } = i18n;

const eventStyler = ({
  event,
}: {
  event: SchedulerEvent & { $backgroundColor?: string };
}) => ({
  style: { backgroundColor: event.$backgroundColor },
});

export function Calendar(props: ViewProps<CalendarView>) {
  const { meta, dataStore } = props;
  const { view: metaView, fields: metaFields, perms: metaPerms } = meta;
  const {
    eventStart,
    eventStop,
    eventLength = 1,
    colorBy,
    mode: initialMode = "month",
  } = metaView;

  l10n.getLocale();
  const session = useSession();
  const maxPerPage = useMemo(
    () => session.data?.api?.pagination?.maxPerPage || -1,
    [session]
  );

  const isDateCalendar = useMemo(
    () => metaFields?.[eventStart]?.type === "DATE",
    [metaFields, eventStart]
  );

  const components = useMemo(() => {
    const timeComponents = isDateCalendar
      ? {
          dayColumnWrapper: () => null,
          timeSlotWrapper: () => null,
          timeGutterWrapper: () => null,
        }
      : {};
    return {
      week: {
        header: ({ date, localizer }: any) => {
          return localizer.format(date, "ddd D");
        },
      },
      toolbar: () => null,
      ...timeComponents,
    };
  }, [isDateCalendar]);

  const searchFieldNames = useMemo(() => {
    const schemaFieldNames = Object.keys(metaFields || {});
    const viewFieldNames = [eventStart, eventStop, colorBy].filter(
      (field) => field
    ) as string[];
    return [...new Set([...schemaFieldNames, ...viewFieldNames])];
  }, [metaFields, eventStart, eventStop, colorBy]);

  const [calendarDate, setCalendarDate] = useState<Date>(() => new Date());
  const [calendarMode, setCalendarMode] = useState<View>(initialMode);

  const { start: calendarStart, end: calendarEnd } = useMemo(() => {
    return getTimes(calendarDate, calendarMode);
  }, [calendarDate, calendarMode]);

  const filter = useMemo(() => {
    const startCriteria: Criteria = {
      operator: "and",
      criteria: [
        {
          fieldName: eventStart,
          operator: ">=",
          value: calendarStart,
        },
        {
          fieldName: eventStart,
          operator: "<",
          value: calendarEnd,
        },
      ],
    };
    const stopCriteria: Criteria | null = eventStop
      ? ({
          operator: "and",
          criteria: [
            {
              fieldName: eventStop,
              operator: ">=",
              value: calendarStart,
            },
            {
              fieldName: eventStart, // include intermediate events: RM-31366
              operator: "<",
              value: calendarEnd,
            },
          ],
        } as Criteria)
      : null;
    return stopCriteria
      ? ({
          operator: "or",
          criteria: [startCriteria, stopCriteria],
        } as Criteria)
      : startCriteria;
  }, [calendarStart, calendarEnd, eventStart, eventStop]);

  const handleRefresh = useCallback(
    async () =>
      await dataStore.search({
        filter,
        fields: searchFieldNames,
        limit: maxPerPage,
      }),
    [dataStore, filter, searchFieldNames, maxPerPage]
  );

  useAsync(handleRefresh, [dataStore, filter]);

  const unfilteredCalendarEvents: SchedulerEvent[] = useMemo(() => {
    return (dataStore.records || []).map((record) => {
      const { id, name: title } = record;
      const start = new Date(record[eventStart] as string);
      const recordStop = eventStop && (record[eventStop] as string);
      const end = recordStop
        ? new Date(recordStop)
        : addDate(start, eventLength, "hours");
      return {
        id,
        title,
        start,
        end,
        record,
        allDay: isDateCalendar,
      } as SchedulerEvent;
    });
  }, [dataStore.records, eventStart, eventStop, eventLength, isDateCalendar]);

  const handleNavigationChange = useCallback((date: Date) => {
    setCalendarDate(date);
  }, []);

  const handleViewChange = useCallback((view: View) => {
    setCalendarMode(view);
  }, []);

  // Filters

  const colorByField = colorBy && metaFields?.[colorBy];

  const [filters, setFilters] = useState<Filter[]>([]);

  useEffect(() => {
    colorByField &&
      setFilters(getEventFilters(unfilteredCalendarEvents || [], colorByField));
  }, [unfilteredCalendarEvents, colorByField]);

  const handleFilterChange = useCallback((ind: number) => {
    if (ind > -1) {
      setFilters((filters) =>
        filters.map((filter, index) =>
          index === ind ? { ...filter, checked: !filter.checked } : filter
        )
      );
    }
  }, []);

  const calendarEvents: SchedulerEvent[] = useMemo(() => {
    const checkedFilters = filters.filter((x) => x.checked);
    const showAll = checkedFilters.length === 0;
    return unfilteredCalendarEvents.reduce(
      (list: object[], event: SchedulerEvent) => {
        const filter = (showAll ? filters : checkedFilters).find(
          (filter: Filter) => filter.match!(event)
        );
        return filter || showAll
          ? [
              ...list,
              {
                ...event,
                $backgroundColor: (filter || {}).color || DEFAULT_COLOR,
              },
            ]
          : list;
      },
      []
    ) as SchedulerEvent[];
  }, [unfilteredCalendarEvents, filters]);

  // Toobar

  const handleNav = useCallback(
    (amount: 1 | -1) => {
      setCalendarDate((date) => addDate(date, amount, calendarMode));
    },
    [calendarMode]
  );

  const handleNext = useCallback(() => handleNav(1), [handleNav]);
  const handlePrev = useCallback(() => handleNav(-1), [handleNav]);

  const handleToday = useCallback(() => {
    setCalendarDate(new Date());
  }, []);

  const calendarTitle = useMemo(() => {
    return formatDate(calendarDate, calendarMode);
  }, [calendarDate, calendarMode]);

  const actions = useMemo<CommandItemProps[]>(() => {
    const views: {
      view: View;
      text: string;
      iconProps: MaterialIconProps;
    }[] = [
      {
        view: "month",
        text: t("Month"),
        iconProps: { icon: "calendar_view_month" },
      },
      {
        view: "week",
        text: t("Week"),
        iconProps: { icon: "calendar_view_week" },
      },
      { view: "day", text: t("Day"), iconProps: { icon: "calendar_view_day" } },
    ];

    const today = new Date();
    const inToday = today >= calendarStart && today <= calendarEnd;

    return [
      ...views.map(({ view, text, iconProps }) => ({
        key: view,
        text: text,
        description: text,
        iconProps,
        iconOnly: false,
        checked: view === calendarMode,
        onClick: () => handleViewChange(view),
      })),
      {
        key: "modeDivider",
        divider: true,
      },
      {
        key: "today",
        text: t("Today"),
        description: t("Today"),
        iconProps: {
          icon: "today",
        },
        iconOnly: false,
        checked: inToday,
        onClick: handleToday,
      },
      {
        key: "refresh",
        text: t("Refresh"),
        description: t("Refresh"),
        iconProps: {
          icon: "refresh",
        },
        iconOnly: false,
        onClick: handleRefresh,
      },
    ];
  }, [
    calendarStart,
    calendarEnd,
    handleToday,
    handleRefresh,
    calendarMode,
    handleViewChange,
  ]);

  const handleDateChange = useCallback(
    (date: Date) => {
      setCalendarDate(date);
      handleNavigationChange(date);
    },
    [handleNavigationChange]
  );

  return (
    <div className={styles.calendar}>
      <ViewToolBar
        meta={meta}
        actions={actions}
        pagination={{
          canPrev: true,
          canNext: true,
          onNext: handleNext,
          onPrev: handlePrev,
          text: calendarTitle,
        }}
      />
      <Box d="flex" flexDirection="row" flexGrow={1}>
        <Box d="flex" p={2} pe={0} className={styles["scheduler-panel"]}>
          <Scheduler
            events={calendarEvents}
            date={calendarDate}
            view={calendarMode}
            onNavigationChange={handleNavigationChange}
            onViewChange={handleViewChange}
            eventStyler={eventStyler}
            components={components}
            style={{ width: "100%" }}
          />
        </Box>
        <Box flex={1} p={2} className={styles["calendar-panel"]}>
          <DatePicker
            selected={calendarDate}
            onChange={handleDateChange}
            {...({ inline: true } as any)}
          />
          <Filters data={filters} onChange={handleFilterChange} />
        </Box>
      </Box>
    </div>
  );
}
