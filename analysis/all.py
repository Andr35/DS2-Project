#!/usr/bin/env python3

#
# This file contains some functions to draw some plots to visualize the results of some experiments.
# The plots are NOT ready for a report. Please use the report.py script to generate the plots included
# in the final report.
#

import os
import click
import itertools
import matplotlib as mpl
import matplotlib.ticker as plticker

from parsey import analyze_results, correct_mean

mpl.use('Agg')
import matplotlib.pyplot as plt


def plot_generic(frame, path, prefix='', x_scale_rounds=True, y_axis='detect_time_average',
                 y_scale_limits=None, y_scale=True, y_label='Detection Time %s', y_unit=None):
    # x axis
    x_axis = ['failure_delta']

    # do not aggregate
    aggregate_none = ['id', 'group', 'seed', 'repetition', 'multicast_max_wait', 'miss_delta', 'multicast_parameter',
                      'expected_first_multicast', 'duration']

    # aggregate, plot on the same graph
    aggregate_same = ['push_pull']

    # aggregate, on different plots
    aggregate_different = ['number_of_nodes', 'simulate_catastrophe', 'n_scheduled_crashes',
                           'gossip_delta', 'enable_multicast', 'ratio_max_wait_and_failure',
                           'ratio_expected_first_multicast', 'ratio_miss_delta', 'pick_strategy']

    # ignored fields -> these are the statistics
    stats = ['correct', 'n_expected_detected_crashes', 'n_correctly_detected_crashes',
             'n_duplicated_reported_crashes', 'n_wrongly_reported_crashes', 'n_reappeared',
             'rate_detected_crashes', 'detect_time_average', 'detect_time_stdev',
             'detect_time_first', 'detect_time_last']

    # security check
    missing = set(frame.columns.values) - set(x_axis + aggregate_none + aggregate_same + aggregate_different + stats)
    if len(missing) != 0:
        click.echo("ERROR: some fields are neither set to be aggregated nor to be ignored -> " + str(missing))

    # aggregate
    aggregation = frame.groupby(x_axis + aggregate_same + aggregate_different, as_index=False).agg(
        {
            'correct': {
                'aggregated_correct': (lambda column: False not in list(column))
            },
            'detect_time_average': {
                'aggregated_detect_time_average': correct_mean
            },
            'rate_detected_crashes': {
                'aggregated_rate_detected_crashes': correct_mean
            },
            'n_duplicated_reported_crashes': {
                'n_duplicated_reported_crashes': 'mean'
            },
            'n_wrongly_reported_crashes': {
                'n_wrongly_reported_crashes': 'mean'
            }
        }
    )
    aggregation.columns = aggregation.columns.droplevel(1)

    # collect different unique values of the fields that I want to aggregate in different plots
    aggregate_different_unique_values = []
    for field in aggregate_different:
        aggregate_different_unique_values.append(aggregation[field].unique())

    # different plots for each unique combination
    for combination in itertools.product(*aggregate_different_unique_values):

        # compute the name for the plot
        name = '__'.join(map(lambda t: '%s-%s' % (t[0], t[1]), zip(aggregate_different, combination)))

        # compute the correct projection on the table
        data = None
        for index, value in enumerate(combination):
            data = (data if data is not None else aggregation).query('%s == %s' % (aggregate_different[index], value))

        # plot only if there are some data
        if len(data) > 0:

            # extract the gossip delta, used to scale the plots axes
            delta = combination[aggregate_different.index('gossip_delta')]

            # decide the scale for the x axis
            x_scaler = delta if x_scale_rounds else 1000
            x_unit = '(rounds of gossip)' if x_scale_rounds else '(seconds)'
            y_scaler = x_scaler if y_scale else 1
            if y_unit is None:
                y_unit = x_unit

            # extract the number of nodes for the legend
            nodes = combination[aggregate_different.index('number_of_nodes')]
            strategy = combination[aggregate_different.index('pick_strategy')]
            catastrophe = combination[aggregate_different.index('simulate_catastrophe')]
            multicast = combination[aggregate_different.index('enable_multicast')]
            ratio_miss_delta = combination[aggregate_different.index('ratio_miss_delta')]
            ratio_max_wait_and_failure = combination[aggregate_different.index('ratio_max_wait_and_failure')]
            ratio_expected_first_multicast = combination[aggregate_different.index('ratio_expected_first_multicast')]

            # create the plot
            figure = plt.figure()
            ax = figure.add_subplot(111)

            # lines -> combinations of push vs push_pull
            aggregate_same_unique_values = []
            for field in aggregate_same:
                aggregate_same_unique_values.append(data[field].unique())
            for correct in [True, False]:
                for tt in itertools.product(*aggregate_same_unique_values):
                    qq = ' and '.join(map(lambda t: '%s == %s' % (t[0], t[1]), zip(aggregate_same, tt)))
                    ff = data.query('correct == %s' % correct).query('%s' % qq)
                    trace = (ff['failure_delta'], ff[y_axis])
                    push_pull = 'push_pull' if tt[aggregate_same.index('push_pull')] else 'push'
                    correct_label = 'correct' if correct else 'wrong'
                    ax.plot(trace[0] / x_scaler, trace[1] / y_scaler,
                            label='%s [%s]' % (push_pull, correct_label),
                            marker='o' if correct else 'x')

            # labels, title, axes
            ax.legend(shadow=True)
            ax.set_title('n=%d, st=%s, cat=%s, mc=%s, tg=%.1fs, mr=%s, rw=%s, r1m=%s' %
                         (nodes, strategy, 'T' if catastrophe else 'F', 'T' if multicast else 'F', delta / 1000,
                          ratio_miss_delta, ratio_max_wait_and_failure, ratio_expected_first_multicast))
            ax.set_xlabel('Failure Time %s' % x_unit)
            ax.set_ylabel(y_label % y_unit)
            ax.tick_params(axis='both', which='major')
            ax.grid(True)
            ax.xaxis.set_major_locator(plticker.MultipleLocator(base=1))

            # set the correct limits for the y axis
            if y_scale_limits is not None:
                ax.set_ylim(y_scale_limits)

            # save plot
            figure.savefig(path + prefix + name + '.png', bbox_inches='tight')
            plt.close(figure)


def plot_miss_delta(frame, path, prefix='', y_scaler_enable=True, y_scale_rounds=True, y_axis='detect_time_average',
                    y_scale_limits=None, y_label='Detection Time %s'):
    # x axis
    x_axis = ['ratio_miss_delta']

    # do not aggregate
    aggregate_none = ['id', 'group', 'seed', 'repetition', 'multicast_max_wait', 'miss_delta', 'multicast_parameter',
                      'expected_first_multicast', 'duration', 'miss_delta']

    # aggregate, plot on the same graph
    aggregate_same = ['push_pull']

    # aggregate, on different plots
    aggregate_different = ['number_of_nodes', 'failure_delta', 'simulate_catastrophe', 'n_scheduled_crashes',
                           'gossip_delta', 'enable_multicast', 'ratio_max_wait_and_failure',
                           'ratio_expected_first_multicast', 'pick_strategy']

    # ignored fields -> these are the statistics
    stats = ['correct', 'n_expected_detected_crashes', 'n_correctly_detected_crashes',
             'n_duplicated_reported_crashes', 'n_wrongly_reported_crashes', 'n_reappeared',
             'rate_detected_crashes', 'detect_time_average', 'detect_time_stdev',
             'detect_time_first', 'detect_time_last']

    # security check
    missing = set(frame.columns.values) - set(x_axis + aggregate_none + aggregate_same + aggregate_different + stats)
    if len(missing) != 0:
        click.echo("ERROR: some fields are neither set to be aggregated nor to be ignored -> " + str(missing))

    # aggregate
    aggregation = frame.groupby(x_axis + aggregate_same + aggregate_different, as_index=False).agg(
        {
            'correct': {
                'aggregated_correct': (lambda column: False not in list(column))
            },
            'detect_time_average': {
                'aggregated_detect_time_average': correct_mean
            },
            'rate_detected_crashes': {
                'aggregated_rate_detected_crashes': correct_mean
            },
            'n_duplicated_reported_crashes': {
                'n_duplicated_reported_crashes': 'mean'
            },
            'n_wrongly_reported_crashes': {
                'n_wrongly_reported_crashes': 'mean'
            }
        }
    )
    aggregation.columns = aggregation.columns.droplevel(1)

    # collect different unique values of the fields that I want to aggregate in different plots
    aggregate_different_unique_values = []
    for field in aggregate_different:
        aggregate_different_unique_values.append(aggregation[field].unique())

    # different plots for each unique combination
    for combination in itertools.product(*aggregate_different_unique_values):

        # compute the name for the plot
        name = '__'.join(map(lambda t: '%s-%s' % (t[0], t[1]), zip(aggregate_different, combination)))

        # compute the correct projection on the table
        data = None
        for index, value in enumerate(combination):
            data = (data if data is not None else aggregation).query('%s == %s' % (aggregate_different[index], value))

        # plot only if there are some data
        if len(data) > 0:

            # extract the gossip delta, used to scale the plots axes
            delta = combination[aggregate_different.index('gossip_delta')]

            # extract the number of nodes for the legend
            nodes = combination[aggregate_different.index('number_of_nodes')]
            failure_delta = combination[aggregate_different.index('failure_delta')]
            strategy = combination[aggregate_different.index('pick_strategy')]
            catastrophe = combination[aggregate_different.index('simulate_catastrophe')]
            multicast = combination[aggregate_different.index('enable_multicast')]
            ratio_max_wait_and_failure = combination[aggregate_different.index('ratio_max_wait_and_failure')]
            ratio_expected_first_multicast = combination[aggregate_different.index('ratio_expected_first_multicast')]

            # decide the scale for the x axis
            if y_scaler_enable:
                y_scaler = delta if y_scale_rounds else 1000
                y_unit = '(rounds of gossip)' if y_scale_rounds else '(seconds)'
            else:
                y_scaler = 1
                y_unit = ''

            # create the plot
            figure = plt.figure()
            ax = figure.add_subplot(111)

            # lines -> combinations of push vs push_pull
            aggregate_same_unique_values = []
            for field in aggregate_same:
                aggregate_same_unique_values.append(data[field].unique())
            for correct in [True, False]:
                for tt in itertools.product(*aggregate_same_unique_values):
                    qq = ' and '.join(map(lambda t: '%s == %s' % (t[0], t[1]), zip(aggregate_same, tt)))
                    ff = data.query('correct == %s' % correct).query('%s' % qq)
                    trace = (ff['ratio_miss_delta'], ff[y_axis])
                    push_pull = 'push_pull' if tt[aggregate_same.index('push_pull')] else 'push'
                    correct_label = 'correct' if correct else 'wrong'
                    ax.plot(trace[0], trace[1] / y_scaler,
                            label='%s [%s]' % (push_pull, correct_label),
                            marker='o' if correct else 'x')

            # labels, title, axes
            ax.legend(shadow=True)
            ax.set_title('n=%d, fd=%sms, st=%s, cat=%s, mc=%s, tg=%.1fs, rw=%s, r1m=%s' %
                         (nodes, failure_delta, strategy, 'T' if catastrophe else 'F', 'T' if multicast else 'F',
                          delta / 1000, ratio_max_wait_and_failure, ratio_expected_first_multicast))
            ax.set_xlabel('Ratio Miss Delta')
            ax.set_ylabel(y_label % y_unit)
            ax.tick_params(axis='both', which='major')
            ax.grid(True)

            # set the correct limits for the y axis
            if y_scale_limits is not None:
                ax.set_ylim(y_scale_limits)

            # save plot
            figure.savefig(path + prefix + name + '.png', bbox_inches='tight')
            plt.close(figure)


@click.command()
@click.option('--x-scale-gossip-rounds', help='Scale the x axis in rounds of gossip (instead of seconds).',
              prompt=False, default=True, type=bool)
@click.option('--miss-delta', help='Analyze different miss_delta values.',
              prompt=False, default=False, type=bool)
@click.option('--reports-path', help='Base path where to find the reports.', prompt=True)
@click.option('--output-path', help='Directory where to store the result of the analysis.', prompt=True)
def main(miss_delta, x_scale_gossip_rounds, reports_path, output_path):
    """
    Analyze the results of the experiments and produces
    useful plots to include in the final report.
    """

    # create directory for the results
    if not os.path.exists(output_path):
        os.makedirs(output_path)

    # analyze the results
    frame = analyze_results(reports_path)
    frame.to_csv(output_path + os.sep + 'results.csv', index=False)

    if not miss_delta:
        # plot 1: average detection time
        plot_generic(
            frame=frame,
            path=output_path + os.sep,
            prefix='correct__',
            x_scale_rounds=x_scale_gossip_rounds,
            y_scale_limits=([0, 60] if x_scale_gossip_rounds else [0, 20]),
        )

        # plot 2: percentage correctly detected
        plot_generic(
            frame=frame,
            path=output_path + os.sep,
            prefix='percentage__',
            x_scale_rounds=x_scale_gossip_rounds,
            y_axis='rate_detected_crashes',
            y_scale_limits=[0, 1.1],
            y_scale=False,
            y_label='Correct Detected Failures Rate %s',
            y_unit=''
        )

    else:
        # plot 1: average detection time
        plot_miss_delta(
            frame=frame,
            path=output_path + os.sep,
            prefix='m_correct__',
            y_scaler_enable=True,
            y_scale_rounds=x_scale_gossip_rounds,
            y_scale_limits=([0, 70]),
        )

        # plot 2: percentage correctly detected
        plot_miss_delta(
            frame=frame,
            path=output_path + os.sep,
            prefix='m_percentage__',
            y_scaler_enable=False,
            y_axis='rate_detected_crashes',
            y_scale_limits=[0, 1.1],
            y_label='Correct Detected Failures Rate %s'
        )


# entry point for the script
if __name__ == '__main__':
    main()
