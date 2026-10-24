//go:build !darwin || ios

// SPDX-License-Identifier: AGPL-3.0-only
package physicalproxy

import (
	"context"
	"errors"
	"net"
)

func physicalCampusReady(context.Context) error { return errors.New("physical_campus_unsupported") }
func physicalCampusDial(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("physical_campus_unsupported")
}
