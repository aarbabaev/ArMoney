import "@angular/compiler";
import { Injector, runInInjectionContext } from "@angular/core";
import { NavigationEnd, Router } from "@angular/router";
import { Subject } from "rxjs";
import { expect, it } from "vitest";
import { App } from "../src/app/app";
import { Bank } from "../src/app/core/bank";

it("tracks callback entry and exit from completed router navigation", () => {
  const events = new Subject<NavigationEnd>();
  const injector = Injector.create({
    providers: [
      { provide: Bank, useValue: {} },
      { provide: Router, useValue: { url: "/", events } },
    ],
  });
  try {
    const app = runInInjectionContext(injector, () => new App());
    expect(app.isCallback()).toBe(false);
    events.next(new NavigationEnd(1, "/oauth/callback?state=synthetic", "/oauth/callback?state=synthetic"));
    expect(app.isCallback()).toBe(true);
    events.next(new NavigationEnd(2, "/home", "/home"));
    expect(app.isCallback()).toBe(false);
  } finally {
    injector.destroy();
  }
});

it("recognizes a callback when the router has already completed initial navigation", () => {
  const injector = Injector.create({
    providers: [
      { provide: Bank, useValue: {} },
      { provide: Router, useValue: { url: "/oauth/callback", events: new Subject() } },
    ],
  });
  try {
    expect(runInInjectionContext(injector, () => new App()).isCallback()).toBe(true);
  } finally {
    injector.destroy();
  }
});
