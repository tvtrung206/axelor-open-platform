import { PhoneNumberUtil } from "google-libphonenumber";
import { useAtomValue } from "jotai";
import {
  ChangeEvent,
  FocusEvent,
  InputHTMLAttributes,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  CountryData,
  CountrySelectorDropdown,
  FlagImage,
  defaultCountries,
  getActiveFormattingMask,
  usePhoneInput,
} from "react-international-phone";

import { Box, Button, Input, Portal, clsx } from "@axelor/ui";

import { Icon } from "@/components/icon";
import { i18n } from "@/services/client/i18n";
import { _findLocale, l10n } from "@/services/client/l10n";
import { FieldControl, FieldProps } from "../../builder";
import { useInput } from "../../builder/hooks";
import { FALLBACK_COUNTRIES, FLAGS, FLAG_SOURCES } from "./utils";

import "react-international-phone/style.css";
import styles from "./phone.module.scss";

const phoneUtil = PhoneNumberUtil.getInstance();

const isPhoneValid = (phone: string) => {
  try {
    return phoneUtil.isValidNumber(phoneUtil.parseAndKeepRawInput(phone));
  } catch (error) {
    return false;
  }
};

export function Phone({
  inputProps,
  ...props
}: FieldProps<string> & {
  inputProps?: Pick<
    InputHTMLAttributes<HTMLInputElement>,
    "type" | "autoComplete" | "placeholder" | "onFocus"
  >;
}) {
  const { schema, readonly, widgetAtom, valueAtom, invalid } = props;
  const { uid, placeholder: _placeholder, widgetAttrs } = schema;
  const {
    placeholderNumberType,
    initialCountry,
    preferredCountries: _preferredCountries,
    onlyCountries: _onlyCountries,
  }: {
    placeholderNumberType?: "FIXED_LINE" | "MOBILE";
    initialCountry?: string;
    preferredCountries?: string;
    onlyCountries?: string;
  } = widgetAttrs;

  const { attrs } = useAtomValue(widgetAtom);
  const { focus, required } = attrs;

  const locale = l10n.getLocale();

  const onlyCountries = useMemo(
    () =>
      _onlyCountries?.split(/\W+/).map((country) => country.toLowerCase()) ??
      [],
    [_onlyCountries],
  );

  const defaultCountry = useMemo(() => {
    let defaultCountry = initialCountry;

    if (!defaultCountry) {
      // If user locale has no country code, look for a match in `navigator.languages`.
      const [
        language,
        country = _findLocale(
          navigator.languages.filter((language) => language.split("-")[1]),
          locale,
          (language) => language.split("-")[0],
        )
          ?.split("-")[1]
          ?.toLowerCase(),
      ] = locale.split("-").map((value) => value.toLowerCase());
      defaultCountry = country ?? FALLBACK_COUNTRIES[language] ?? language;
    }

    if (onlyCountries.length && !onlyCountries.includes(defaultCountry)) {
      defaultCountry = onlyCountries[0];
    }

    return defaultCountry;
  }, [initialCountry, locale, onlyCountries]);

  const preferredCountries = useMemo(() => {
    if (_preferredCountries) {
      return _preferredCountries
        .split(/\W+/)
        .map((country) => country.toLowerCase());
    }

    return [
      ...new Set([
        defaultCountry,
        ...navigator.languages
          .map((language) => language.split("-")[1]?.toLowerCase())
          .filter(
            (country) =>
              country &&
              (!onlyCountries.length || onlyCountries.includes(country)),
          ),
      ]),
    ];
  }, [_preferredCountries, defaultCountry, onlyCountries]);

  const value = useAtomValue(valueAtom);
  const noPrefix = !!value && !value.startsWith("+");

  const {
    text: _text,
    onChange,
    onBlur: _onBlur,
    onKeyDown,
    setValue,
  } = useInput(valueAtom, {
    schema,
  });

  const text = useMemo(() => {
    return noPrefix ? _text.replace(/^0/, "") : _text;
  }, [_text, noPrefix]);

  const {
    inputValue,
    phone,
    country,
    setCountry,
    handlePhoneValueChange,
    inputRef,
  } = usePhoneInput({
    defaultCountry,
    value: text,
    onChange: ({ phone, country }) => {
      // If case of only dial code, set empty value instead.
      onChange({
        target: { value: phone !== `+${country.dialCode}` ? phone : "" },
      } as ChangeEvent<HTMLInputElement>);
    },
    disableDialCodeAndPrefix: noPrefix,
  });

  // If case of only dial code, set empty value instead.
  const onBlur = useCallback(() => {
    _onBlur({
      target: { value: phone !== `+${country.dialCode}` ? phone : "" },
    } as FocusEvent<HTMLInputElement>);
  }, [_onBlur, country.dialCode, phone]);

  const placeholder = useMemo(() => {
    if (_placeholder) return _placeholder;

    const { dialCode } = country;

    // For placeholder, we just need the default mask for specified country.
    let phoneFormat = getActiveFormattingMask({ phone: "", country });

    // Special case for French mobile phone numbers
    if (
      dialCode === "33" &&
      placeholderNumberType?.toUpperCase() === "MOBILE"
    ) {
      phoneFormat = phoneFormat.replace(".", "6");
    }

    let currentNumber = 0;
    const numbers = phoneFormat.replace(/\./g, () => `${++currentNumber % 10}`);
    const placeholder = noPrefix ? numbers : `+${dialCode} ${numbers}`;

    return placeholder;
  }, [_placeholder, country, noPrefix, placeholderNumberType]);

  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const [showDropdown, setShowDropdown] = useState<boolean>(false);

  const toggleTimeRef = useRef<number>(0);

  const toggleDropdown = useCallback(() => {
    toggleTimeRef.current = new Date().getTime();
    setShowDropdown(!showDropdown);
  }, [showDropdown]);

  // Position for portaled dropdown
  const dropdownPos = useMemo(() => {
    if (!showDropdown) return {};
    const { bottom, left } = buttonRef.current?.getBoundingClientRect() ?? {};
    return { top: bottom, left };
  }, [showDropdown]);

  // Close dropdown on scroll.
  useEffect(() => {
    if (!showDropdown) return;

    const handleScroll = (event: Event) => {
      if (!dropdownRef.current?.contains(event.target as Element)) {
        setShowDropdown(false);
      }
    };

    window.addEventListener("scroll", handleScroll, true);
    return () => window.removeEventListener("scroll", handleScroll, true);
  }, [showDropdown]);

  const countries = useMemo(() => {
    // Filter out countries that are not in `onlyCountries`, if specified.
    let countries = onlyCountries.length
      ? defaultCountries.filter((country) => onlyCountries.includes(country[1]))
      : defaultCountries;

    // Translate country names
    countries = countries.map((country) => {
      const [name, ...rest] = country;
      return [i18n.get(name), ...rest] as CountryData;
    });
    countries.sort((a, b) => a[0].localeCompare(b[0]));

    return countries;
  }, [onlyCountries]);

  const countryIso2 = useMemo(() => {
    const { iso2 } = country;
    return onlyCountries.length && !onlyCountries.includes(iso2)
      ? defaultCountry
      : iso2;
  }, [country, defaultCountry, onlyCountries]);

  const hasValue = text && text === phone;
  const showButton = hasValue || !readonly;

  return (
    <FieldControl {...props} className={styles.container}>
      <Box className={clsx(styles.phone, { [styles.readonly]: readonly })}>
        {showButton && (
          <>
            <Box title={i18n.get(country.name)}>
              <Button
                ref={buttonRef}
                className={styles.country}
                onMouseDown={(event) => {
                  event.preventDefault();
                  if (event.button === 0) {
                    toggleDropdown();
                  }
                }}
                onTouchStart={(event) => {
                  event.preventDefault();
                  toggleDropdown();
                }}
                disabled={readonly}
              >
                <FlagImage
                  iso2={countryIso2}
                  src={FLAG_SOURCES[countryIso2]}
                  className={styles.flag}
                />
                {!readonly && (
                  <Icon
                    icon={`arrow_drop_${showDropdown ? "up" : "down"}`}
                    className={styles.arrow}
                  />
                )}
              </Button>
            </Box>
            {!readonly && (
              <Portal>
                <Box
                  ref={dropdownRef}
                  className={styles.dropdown}
                  style={dropdownPos}
                >
                  <CountrySelectorDropdown
                    show={showDropdown}
                    selectedCountry={countryIso2}
                    onSelect={(country) => {
                      if (country.iso2 !== countryIso2) {
                        setValue(null);
                        setCountry(country.iso2);
                      }
                      setShowDropdown(false);
                      inputRef.current?.focus();
                    }}
                    onClose={() => {
                      if (new Date().getTime() - toggleTimeRef.current > 200) {
                        setTimeout(() => setShowDropdown(false), 100);
                      }
                    }}
                    preferredCountries={preferredCountries}
                    countries={countries}
                    flags={FLAGS}
                    listItemFlagClassName={styles.flag}
                  />
                </Box>
              </Portal>
            )}
          </>
        )}
        {readonly ? (
          <Box
            as="a"
            target="_blank"
            href={`tel:${noPrefix ? value : phone}`}
            className={styles.link}
          >
            {hasValue && inputValue}
          </Box>
        ) : (
          <Input
            ref={inputRef}
            {...(focus && { key: "focused" })}
            data-input
            type="text"
            id={uid}
            autoFocus={focus}
            placeholder={placeholder}
            value={inputValue}
            invalid={invalid}
            required={required}
            onKeyDown={onKeyDown}
            onChange={handlePhoneValueChange}
            onBlur={onBlur}
            className={clsx(styles.input, {
              [styles.invalid]: hasValue && !isPhoneValid(phone),
            })}
            {...inputProps}
          />
        )}
      </Box>
    </FieldControl>
  );
}
