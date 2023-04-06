import { uniqueId } from "lodash";
import { useCallback, useEffect, useMemo } from "react";

import { Box, CommandBar, CommandItemProps } from "@axelor/ui";
import { GridState } from "@axelor/ui/grid";

import { PageText } from "@/components/page-text";
import { DataStore } from "@/services/client/data-store";
import { DataContext, DataRecord } from "@/services/client/data.types";
import { showPopup, usePopupOptions } from "@/view-containers/view-popup";

import { useDataStore } from "../use-data-store";
import { initTab } from "../use-tabs";

export type SelectorOptions = {
  model: string;
  title: string;
  multiple?: boolean;
  viewName?: string;
  domain?: string;
  context?: DataContext;
  onSelect?: (records: DataRecord[]) => void;
};

export function useSelector() {
  return useCallback(async function showSelector(options: SelectorOptions) {
    const { title, model, viewName, multiple, domain, context, onSelect } =
      options;
    const tab = await initTab({
      name: uniqueId("$selector"),
      title,
      model,
      viewType: "grid",
      views: [{ type: "grid", name: viewName }],
      params: {
        popup: true,
        "show-toolbar": false,
        "_popup-edit-icon": false,
        "_popup-multi-select": multiple,
      },
      domain,
      context,
    });

    if (!tab) return;

    const close = await showPopup({
      tab,
      open: true,
      onClose: () => {},
      header: () => <Header />,
      handler: () => (
        <Handler
          close={() => close()}
          multiple={multiple}
          onSelect={onSelect}
        />
      ),
    });
  }, []);
}

function Handler({
  multiple,
  close,
  onSelect,
}: {
  multiple?: boolean;
  close: () => void;
  onSelect?: (records: DataRecord[]) => void;
}) {
  const { data } = usePopupOptions();

  const onSelectionChange = useCallback(
    (index: number, records: DataRecord[]) => {
      if (index === 0 && multiple) return;
      onSelect?.(records);
      close();
    },
    [close, multiple, onSelect]
  );

  useEffect(() => {
    const state = data as GridState;
    const index = state?.selectedCell?.[1] ?? 0;
    const records =
      state?.selectedRows?.map((index) => state.rows[index].record) ?? [];
    if (records.length) {
      onSelectionChange(index, records);
    }
  }, [data, onSelectionChange]);

  return null;
}

function Header() {
  const { dataStore } = usePopupOptions();
  if (dataStore) {
    return <SelectorHeader dataStore={dataStore} />;
  }
  return null;
}

function SelectorHeader({ dataStore }: { dataStore: DataStore }) {
  const page = useDataStore(dataStore, (state) => state.page);
  const { offset = 0, limit = 0, totalCount = 0 } = page;

  const onNext = useCallback(() => {
    const nextOffset = Math.min(offset + limit, totalCount);
    dataStore.search({ offset: nextOffset });
  }, [dataStore, limit, offset, totalCount]);

  const onPrev = useCallback(() => {
    const nextOffset = Math.max(offset - limit, 0);
    dataStore.search({ offset: nextOffset });
  }, [dataStore, limit, offset]);

  const commands = useMemo(() => {
    const items: CommandItemProps[] = [
      {
        key: "prev",
        iconOnly: true,
        iconProps: {
          icon: "navigate_before",
        },
        disabled: offset === 0,
        onClick: onPrev,
      },
      {
        key: "next",
        iconOnly: true,
        iconProps: {
          icon: "navigate_next",
        },
        disabled: offset + limit >= totalCount,
        onClick: onNext,
      },
    ];
    return items;
  }, [limit, offset, onNext, onPrev, totalCount]);

  return (
    <Box d="flex" alignItems="center" g={2}>
      <PageText dataStore={dataStore} />
      <CommandBar items={commands} />
    </Box>
  );
}
