import { Component, OnInit } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { Observable } from 'rxjs/Observable';

import { ErrorService } from '../error.service';
import { ExchangeCurrencyService } from '../exchange-currency.service';
import { ExchangeCurrency } from '../exchange-currency';
import { FixedPriceAlertsService } from '../fixed-price-alerts.service';

@Component({
  selector: 'app-new-fixed-price-alert',
  templateUrl: './new-fixed-price-alert.component.html',
  styleUrls: ['./new-fixed-price-alert.component.css']
})
export class NewFixedPriceAlertComponent implements OnInit {

  form: FormGroup;

  // TODO: load them from the server?
  availableExchanges = ['BITSO', 'BITTREX'];
  availableMarkets: Observable<string[]>;
  availableCurrencies: Observable<ExchangeCurrency[]>;

  constructor(
    private formBuilder: FormBuilder,
    private exchangeCurrencyService: ExchangeCurrencyService,
    private fixedPriceAlertsService: FixedPriceAlertsService,
    private router: Router,
    public errorService: ErrorService) {

    this.createForm();
  }

  ngOnInit() {
  }

  private createForm() {
    const priceValidators = [Validators.min(0.00000001), Validators.max(99999999)];

    this.form = this.formBuilder.group({
      exchange: ['', Validators.required],
      market: [null, Validators.required],
      currency: [null, Validators.required],
      condition: [null, Validators.required],
      price: [null, [
        Validators.required,
        ...priceValidators
      ]],
      basePrice: [null, priceValidators]
    });

    this.disableCurrency();
    this.disableMarket();
  }

  private disableMarket() {
    this.form.get('market').disable();
  }

  private enableMarket() {
    this.form.get('market').enable();
  }

  private disableCurrency() {
    this.form.get('currency').disable();
  }

  private enableCurrency() {
    this.form.get('currency').enable();
  }

  // TODO: reusable function
  private sortStrings(array: string[]): string[] {
    return array.sort((a, b) => {
      if (a < b) {
        return -1;
      } else if (a > b) {
        return 1;
      } else {
        return 0;
      }
    });
  }

  // TODO: reusable function
  private sortCurrencies(array: ExchangeCurrency[]): ExchangeCurrency[] {
    return array.sort((a, b) => {
      if (a.currency < b.currency) {
        return -1;
      } else if (a.currency > b.currency) {
        return 1;
      } else {
        return 0;
      }
    });
  }

  onExchangeSelected(exchange: string) {
    this.availableCurrencies = null;
    this.availableMarkets = this.exchangeCurrencyService
      .getMarkets(exchange)
      .map(markets => this.sortStrings(markets));

    this.disableCurrency();
    this.enableMarket();
  }

  onMarketSelected(market: string) {
    const exchange = this.form.get('exchange').value;
    this.availableCurrencies = this.exchangeCurrencyService
      .getCurrencies(exchange, market)
      .map(currencies => this.sortCurrencies(currencies));

    this.enableCurrency();
  }

  onSubmit() {
    this.fixedPriceAlertsService.create(
        this.form.get('currency').value,
        this.form.get('price').value,
        this.form.get('condition').value === 'above',
        this.form.get('basePrice').value)
      .subscribe(
        response => this.onSubmitSuccess(response),
        response => this.errorService.renderServerErrors(this.form, response)
      );
  }

  protected onSubmitSuccess(response: any) {
    // TODO: add success message
    this.router.navigate(['/fixed-price-alerts']);
  }
}
